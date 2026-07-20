package com.ylcloud.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.UserMemoryExtractionTask;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Service
public class UserMemoryExtractionService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryExtractionService.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|passwd|secret|api[-_ ]?key|token|身份证|银行卡|密码|密钥|验证码|病历|诊断)");
    private static final String SYSTEM_PROMPT = "你是用户长期记忆抽取器。只能提取用户原文明确表达的稳定事实、偏好、约束或已确认决定；禁止根据助手回答推断。" +
            "忽略密码、令牌、密钥、身份证、银行卡、医疗等敏感信息。返回 JSON 数组，每项含 type、key、content、confidence、userConfirmed；无内容返回 []。";

    private final UserMemoryExtractionTaskMapper taskMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final UserMemoryService memoryService;
    private final RagModelClient modelClient;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public UserMemoryExtractionService(UserMemoryExtractionTaskMapper taskMapper, KnowledgeChatMessageMapper messageMapper,
                                       UserMemoryService memoryService, RagModelClient modelClient, RagProperties properties,
                                       ObjectMapper objectMapper, @Qualifier("ragTaskExecutor") Executor executor) {
        this.taskMapper = taskMapper; this.messageMapper = messageMapper; this.memoryService = memoryService;
        this.modelClient = modelClient; this.properties = properties; this.objectMapper = objectMapper; this.executor = executor;
    }

    public void enqueue(KnowledgeChatMessage assistant) {
        if(assistant == null || assistant.getSourceMessageId() == null || !memoryService.enabled(assistant.getUserId())) return;
        LocalDateTime now = LocalDateTime.now();
        taskMapper.enqueue(assistant.getId(),assistant.getUserId(),assistant.getSessionId(),assistant.getSourceMessageId(),now);
        UserMemoryExtractionTask task = taskMapper.getByAssistantMessageId(assistant.getId());
        if(task != null) executor.execute(() -> process(task.getId()));
    }

    public void process(Long taskId) {
        if(taskMapper.claim(taskId,LocalDateTime.now()) == 0) return;
        UserMemoryExtractionTask task = taskMapper.getById(taskId);
        try {
            if(!memoryService.enabled(task.getUserId())) { taskMapper.succeed(taskId,LocalDateTime.now()); return; }
            KnowledgeChatMessage source = messageMapper.getOwned(task.getSourceMessageId(),task.getSessionId(),task.getUserId());
            if(source == null || !"user".equals(source.getRole())) throw new IllegalStateException("Memory source user message is missing");
            for(UserMemoryCandidate candidate : extract(source.getContent())) {
                UserMemoryItem item = memoryService.accept(task.getUserId(),task.getSessionId(),source.getId(),source.getContent(),candidate);
                if(item != null && "PENDING".equals(item.getEmbeddingStatus())) memoryService.processIndex(item.getId());
            }
            taskMapper.succeed(taskId,LocalDateTime.now());
        } catch(Exception ex) {
            taskMapper.fail(taskId,error(ex),LocalDateTime.now().plusSeconds(backoff(task)),LocalDateTime.now());
            log.warn("User memory extraction failed: taskId={}",taskId,ex);
        }
    }

    List<UserMemoryCandidate> extract(String userText) throws Exception {
        RagGenerateRequest request = new RagGenerateRequest();
        request.setModel(properties.getModelService().getChatModel()); request.setSystemPrompt(SYSTEM_PROMPT);
        request.setPrompt("用户原文：\n" + userText); request.setMaxTokens(700); request.setTemperature(0.0);
        RagGenerateResponse response = modelClient.generate(request);
        String json = stripFence(response == null ? null : response.getText());
        if(json == null || json.isBlank()) return List.of();
        JsonNode root = objectMapper.readTree(json);
        if(!root.isArray()) throw new IllegalStateException("Memory extraction response must be a JSON array");
        List<UserMemoryCandidate> result = new ArrayList<>();
        for(JsonNode node : root) {
            String content = text(node,"content"); String key = text(node,"key");
            double confidence = node.path("confidence").asDouble(0);
            if(content.isBlank() || key.isBlank() || confidence < 0.65 || SENSITIVE.matcher(content + " " + key).find()) continue;
            result.add(new UserMemoryCandidate(text(node,"type").toUpperCase(Locale.ROOT),key,content,confidence,node.path("userConfirmed").asBoolean(false)));
            if(result.size() == 5) break;
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${ylcloud.memory.extraction-delay-ms:30000}", initialDelayString = "${ylcloud.memory.extraction-initial-delay-ms:20000}")
    public void recover() {
        taskMapper.recoverInterrupted(LocalDateTime.now().minusMinutes(10),LocalDateTime.now());
        Integer configured = properties.getMemory().getMaxRetries();
        int maxRetries = configured == null || configured <= 0 ? 8 : configured;
        taskMapper.listPending(100,maxRetries).forEach(task -> executor.execute(() -> process(task.getId())));
    }

    private String stripFence(String value) { if(value == null) return null; String text = value.trim(); if(text.startsWith("```")) { int first = text.indexOf('\n'); int last = text.lastIndexOf("```"); if(first >= 0 && last > first) text = text.substring(first + 1,last).trim(); } return text; }
    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private long backoff(UserMemoryExtractionTask task) { int attempt = Math.min(10,(task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1); return Math.min(3600,1L << attempt); }
    private String error(Exception ex) { String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); return value.substring(0,Math.min(1000,value.length())); }
}
