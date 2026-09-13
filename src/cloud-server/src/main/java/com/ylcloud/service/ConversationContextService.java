package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.RagChatMessageDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.service.memory.UserMemoryRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationContextService {
    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);
    private static final int SNAPSHOT_VERSION = 2;
    private static final String SUMMARY_SYSTEM_PROMPT = "你负责压缩对话记忆。只保留用户目标、明确事实、已确认决策、约束、待办和未解决问题；不要补充或推测事实。";

    private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final ConversationTokenEstimator tokenEstimator;
    private final RagModelClient ragModelClient;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final UserMemoryRetrievalService memoryRetrievalService;

    @Autowired
    public ConversationContextService(KnowledgeChatSessionMapper sessionMapper,
                                      KnowledgeChatMessageMapper messageMapper,
                                      ConversationTokenEstimator tokenEstimator,
                                      RagModelClient ragModelClient,
                                      RagProperties ragProperties,
                                      ObjectMapper objectMapper,
                                      UserMemoryRetrievalService memoryRetrievalService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.tokenEstimator = tokenEstimator;
        this.ragModelClient = ragModelClient;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.memoryRetrievalService = memoryRetrievalService;
    }

    ConversationContextService(KnowledgeChatSessionMapper sessionMapper, KnowledgeChatMessageMapper messageMapper,
                               ConversationTokenEstimator tokenEstimator, RagModelClient ragModelClient,
                               RagProperties ragProperties, ObjectMapper objectMapper) {
        this(sessionMapper,messageMapper,tokenEstimator,ragModelClient,ragProperties,objectMapper,null);
    }

    public ConversationContextSnapshot resolve(KnowledgeChatMessage task) {
        if(task.getContextSnapshotJson() != null && !task.getContextSnapshotJson().isBlank()) {
            return readSnapshot(task.getContextSnapshotJson());
        }
        if(task.getSourceMessageId() == null) throw new BaseException("问答任务缺少来源消息");
        KnowledgeChatMessage source = messageMapper.getOwned(task.getSourceMessageId(),task.getSessionId(),task.getUserId());
        if(source == null || !"user".equals(source.getRole())) throw new BaseException("问答任务来源消息不存在");
        KnowledgeChatSession session = sessionMapper.getActive(task.getSessionId(),task.getUserId());
        if(session == null) throw new BaseException("会话不存在");

        List<UserMemoryItem> memories = memoryRetrievalService == null ? List.of()
                : memoryRetrievalService.retrieve(task.getUserId(),source.getContent());

        List<KnowledgeChatMessage> candidates = messageMapper.listContextCandidates(
                task.getSessionId(),task.getUserId(),source.getSequenceNo());
        int maxTokens = positive(ragProperties.getChat().getMaxHistoryTokens(),2048);
        int questionTokens = tokenEstimator.estimate(source.getContent());
        int existingSummaryTokens = tokenEstimator.estimate(session.getRollingSummary());
        int recentBudget = Math.max(0,maxTokens - questionTokens - existingSummaryTokens);
        List<KnowledgeChatMessage> recent = newestWithinBudget(candidates,recentBudget);
        int omittedCount = Math.max(0,candidates.size() - recent.size());
        if(omittedCount > 0) refreshSummary(session,candidates.subList(0,omittedCount));

        int refreshedSummaryTokens = tokenEstimator.estimate(session.getRollingSummary());
        recentBudget = Math.max(0,maxTokens - questionTokens - refreshedSummaryTokens);
        recent = newestWithinBudget(candidates,recentBudget);
        omittedCount = Math.max(0,candidates.size() - recent.size());
        if(omittedCount > 0) refreshSummary(session,candidates.subList(0,omittedCount));

        List<RagChatMessageDTO> history = new ArrayList<>();
        int summaryTokens = 0;
        if(session.getRollingSummary() != null && !session.getRollingSummary().isBlank()) {
            int remaining = Math.max(0,maxTokens - questionTokens - tokenCount(recent));
            RagChatMessageDTO summary = message("system",truncateToTokens("较早对话摘要：\n" + session.getRollingSummary(),remaining));
            summaryTokens = tokenEstimator.estimate(summary.getContent());
            if(!summary.getContent().isBlank()) history.add(summary);
        }
        List<Long> messageIds = new ArrayList<>();
        int historyTokens = 0;
        for(KnowledgeChatMessage item : recent) {
            RagChatMessageDTO historyItem = message(item.getRole(),truncate(item.getContent(),2000));
            history.add(historyItem);
            messageIds.add(item.getId());
            historyTokens += tokenEstimator.estimate(historyItem.getContent());
        }
        int memoryBudget = positive(ragProperties.getMemory().getMaxTokens(),512);
        int memoryTokens = 0;
        List<Long> memoryIds = new ArrayList<>();
        for(UserMemoryItem memory : memories) {
            int remaining = memoryBudget - memoryTokens;
            if(remaining <= 0) break;
            RagChatMessageDTO memoryMessage = message("system",truncateToTokens(
                    "用户长期记忆（" + memory.getMemoryType() + "）：" + memory.getContent(),remaining));
            int used = tokenEstimator.estimate(memoryMessage.getContent());
            if(used <= 0) continue;
            history.add(memoryMessage);
            memoryIds.add(memory.getId());
            memoryTokens += used;
        }
        int totalTokens = summaryTokens + historyTokens + memoryTokens + questionTokens;
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                SNAPSHOT_VERSION,task.getSessionId(),task.getUserId(),task.getId(),source.getId(),
                List.copyOf(messageIds),List.copyOf(history),historyTokens,summaryTokens,totalTokens,
                session.getSummaryVersion() == null ? 0 : session.getSummaryVersion(),
                List.of(),List.copyOf(memoryIds),memoryTokens,"server-token-budget-memory-v2",LocalDateTime.now());
        String json = writeSnapshot(snapshot);
        String hash = sha256(json);
        messageMapper.saveContextSnapshot(task.getId(),json,hash,SNAPSHOT_VERSION,totalTokens);
        task.setContextSnapshotJson(json);
        task.setContextHash(hash);
        task.setContextVersion(SNAPSHOT_VERSION);
        task.setContextTokenCount(totalTokens);
        return snapshot;
    }

    public ConversationContextSnapshot finalizeRetrieval(KnowledgeChatMessage task,
                                                         ConversationContextSnapshot snapshot,
                                                         List<Long> knowledgeChunkIds) {
        List<Long> chunks = knowledgeChunkIds == null ? List.of() : knowledgeChunkIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        ConversationContextSnapshot finalized = new ConversationContextSnapshot(
                snapshot.version(),snapshot.sessionId(),snapshot.userId(),snapshot.assistantMessageId(),snapshot.sourceMessageId(),
                snapshot.messageIds(),snapshot.history(),snapshot.historyTokens(),snapshot.summaryTokens(),snapshot.totalTokens(),
                snapshot.summaryVersion(),chunks,snapshot.memoryIds(),snapshot.memoryTokens(),snapshot.strategy(),snapshot.createdAt());
        String json = writeSnapshot(finalized);
        String hash = sha256(json);
        messageMapper.finalizeContextSnapshot(task.getId(),json,hash);
        task.setContextSnapshotJson(json);
        task.setContextHash(hash);
        return finalized;
    }

    private List<KnowledgeChatMessage> newestWithinBudget(List<KnowledgeChatMessage> candidates, int budget) {
        if(candidates.isEmpty() || budget <= 0) return List.of();
        int used = 0;
        int start = candidates.size();
        for(int i = candidates.size() - 1; i >= 0;) {
            int roundStart = i;
            int roundTokens = tokenEstimator.estimate(candidates.get(i).getContent());
            if("assistant".equals(candidates.get(i).getRole()) && i > 0 && "user".equals(candidates.get(i - 1).getRole())) {
                roundStart = i - 1;
                roundTokens += tokenEstimator.estimate(candidates.get(i - 1).getContent());
            }
            if(used + roundTokens > budget) break;
            start = roundStart;
            used += roundTokens;
            i = roundStart - 1;
        }
        return new ArrayList<>(candidates.subList(start,candidates.size()));
    }

    private void refreshSummary(KnowledgeChatSession session, List<KnowledgeChatMessage> omitted) {
        long summarized = session.getSummaryUptoSequenceNo() == null ? 0 : session.getSummaryUptoSequenceNo();
        List<KnowledgeChatMessage> delta = omitted.stream()
                .filter(message -> message.getSequenceNo() != null && message.getSequenceNo() > summarized).toList();
        if(delta.isEmpty()) return;
        try {
            StringBuilder prompt = new StringBuilder();
            if(session.getRollingSummary() != null && !session.getRollingSummary().isBlank()) {
                prompt.append("已有摘要：\n").append(session.getRollingSummary()).append("\n\n");
            }
            prompt.append("新增对话：\n");
            for(KnowledgeChatMessage message : delta) {
                prompt.append(message.getRole()).append(": ").append(message.getContent()).append('\n');
            }
            RagGenerateRequest request = new RagGenerateRequest();
            request.setModel(ragProperties.getModelService().getChatModel());
            request.setSystemPrompt(SUMMARY_SYSTEM_PROMPT);
            request.setPrompt(prompt.toString());
            request.setMaxTokens(positive(ragProperties.getChat().getSummaryMaxTokens(),512));
            request.setTemperature(0.1);
            RagGenerateResponse response = ragModelClient.generate(request);
            if(response.getText() == null || response.getText().isBlank()) return;
            long upto = delta.get(delta.size() - 1).getSequenceNo();
            int version = session.getSummaryVersion() == null ? 0 : session.getSummaryVersion();
            if(sessionMapper.updateSummary(session.getId(),session.getUserId(),truncate(response.getText().trim(),8000),upto,version,LocalDateTime.now()) > 0) {
                session.setRollingSummary(truncate(response.getText().trim(),8000));
                session.setSummaryUptoSequenceNo(upto);
                session.setSummaryVersion(version + 1);
            }
        } catch(Exception exception) {
            log.warn("Conversation summary update failed: sessionId={}, userId={}",session.getId(),session.getUserId(),exception);
        }
    }

    private RagChatMessageDTO message(String role, String content) {
        RagChatMessageDTO dto = new RagChatMessageDTO(); dto.setRole(role); dto.setContent(content); return dto;
    }
    private int positive(Integer value, int fallback) { return value == null || value <= 0 ? fallback : value; }
    private int tokenCount(List<KnowledgeChatMessage> messages) { return messages.stream().mapToInt(item -> tokenEstimator.estimate(item.getContent())).sum(); }
    private String truncateToTokens(String value, int maxTokens) {
        if(value == null || maxTokens <= 0) return "";
        if(tokenEstimator.estimate(value) <= maxTokens) return value;
        int low = 0, high = value.length();
        while(low < high) {
            int middle = (low + high + 1) / 2;
            if(tokenEstimator.estimate(value.substring(0,middle)) <= maxTokens) low = middle; else high = middle - 1;
        }
        return value.substring(0,low);
    }
    private String truncate(String value, int limit) { return value == null || value.length() <= limit ? value : value.substring(0,limit); }
    private String writeSnapshot(ConversationContextSnapshot snapshot) {
        try { return objectMapper.writeValueAsString(snapshot); }
        catch(JsonProcessingException exception) { throw new BaseException("会话上下文快照无法持久化"); }
    }
    private ConversationContextSnapshot readSnapshot(String json) {
        try { return objectMapper.readValue(json,ConversationContextSnapshot.class); }
        catch(JsonProcessingException exception) { throw new BaseException("会话上下文快照无法恢复"); }
    }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for(byte item : digest) result.append(String.format("%02x",item));
            return result.toString();
        } catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
