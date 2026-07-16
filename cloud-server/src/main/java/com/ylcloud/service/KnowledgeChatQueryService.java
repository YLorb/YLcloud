package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class KnowledgeChatQueryService {
    private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final SpacePermissionService spacePermissionService;
    private final KnowledgeRagQueryService ragQueryService;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public KnowledgeChatQueryService(KnowledgeChatSessionMapper sessionMapper,
                                     KnowledgeChatMessageMapper messageMapper,
                                     SpacePermissionService spacePermissionService,
                                     KnowledgeRagQueryService ragQueryService,
                                     ObjectMapper objectMapper,
                                     @Qualifier("ragTaskExecutor") Executor executor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.spacePermissionService = spacePermissionService;
        this.ragQueryService = ragQueryService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public KnowledgeChatMessageVO submit(Long sessionId, Long userId, KnowledgeChatQueryCreateDTO dto, String requestKey) {
        KnowledgeChatSession session = requireSession(sessionId,userId);
        List<Long> spaceIds = normalizeSpaces(dto.getSpaceIds());
        spaceIds.forEach(spaceId -> spacePermissionService.requireMember(spaceId,userId));
        String normalizedKey = normalizeRequestKey(requestKey);
        KnowledgeChatMessage existing = messageMapper.getByRequestKey(sessionId,normalizedKey);
        if(existing != null) {
            return toVO(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeChatMessage userMessage = new KnowledgeChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setUserId(userId);
        userMessage.setRole("user");
        userMessage.setContent(dto.getQuestion().trim());
        userMessage.setRetryCount(0);
        userMessage.setStatus(StatusConstant.ENABLE);
        userMessage.setCreatetime(now);
        userMessage.setUpdatetime(now);
        messageMapper.insert(userMessage);

        dto.setSpaceIds(spaceIds);
        KnowledgeChatMessage assistant = new KnowledgeChatMessage();
        assistant.setSessionId(sessionId);
        assistant.setUserId(userId);
        assistant.setRole("assistant");
        assistant.setContent("正在生成回答…");
        assistant.setTaskStatus("QUEUED");
        assistant.setRequestKey(normalizedKey);
        assistant.setRequestJson(writeRequest(dto));
        assistant.setRetryCount(0);
        assistant.setStatus(StatusConstant.ENABLE);
        assistant.setCreatetime(now);
        assistant.setUpdatetime(now);
        messageMapper.insert(assistant);
        sessionMapper.touch(session.getId(),userId,now);
        dispatchAfterCommit(assistant.getId());
        return toVO(assistant);
    }

    @Transactional
    public KnowledgeChatMessageVO retry(Long sessionId, Long messageId, Long userId) {
        requireSession(sessionId,userId);
        KnowledgeChatMessage message = messageMapper.getOwned(messageId,sessionId,userId);
        if(message == null || !"assistant".equals(message.getRole())) {
            throw new BaseException("待重试的回答不存在");
        }
        if(!"FAILED".equals(message.getTaskStatus())) {
            throw new BaseException("只有失败的回答可以重试");
        }
        if(messageMapper.retryFailed(messageId) == 0) {
            throw new BaseException("回答状态已变化，请刷新后重试");
        }
        dispatchAfterCommit(messageId);
        message.setTaskStatus("QUEUED");
        message.setErrorMessage(null);
        message.setRetryCount((message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1);
        message.setUpdatetime(LocalDateTime.now());
        return toVO(message);
    }

    public void execute(Long messageId) {
        if(messageMapper.claimQueued(messageId) == 0) {
            return;
        }
        KnowledgeChatMessage message = findQueuedTask(messageId);
        if(message == null) {
            return;
        }
        try {
            KnowledgeChatQueryCreateDTO request = objectMapper.readValue(message.getRequestJson(),KnowledgeChatQueryCreateDTO.class);
            KnowledgeRagQueryDTO ragRequest = new KnowledgeRagQueryDTO();
            ragRequest.setQuestion(request.getQuestion());
            ragRequest.setSpaceIds(request.getSpaceIds());
            ragRequest.setRetrievalMode(request.getRetrievalMode());
            ragRequest.setHistory(request.getHistory());
            KnowledgeRagQueryVO result = ragQueryService.query(ragRequest,message.getUserId());
            String answer = result.getAnswer() == null || result.getAnswer().isBlank()
                    ? "当前知识库中没有足够相关的信息来回答这个问题。" : result.getAnswer().trim();
            String citations = objectMapper.writeValueAsString(result.getCitations() == null ? List.of() : result.getCitations());
            messageMapper.markSuccess(messageId,answer,citations);
        } catch(Exception exception) {
            messageMapper.markFailed(messageId,errorSummary(exception));
        }
    }

    @Scheduled(fixedDelayString = "${ylcloud.chat.recovery-delay-ms:30000}",
            initialDelayString = "${ylcloud.chat.recovery-initial-delay-ms:15000}")
    public void recoverPendingQueries() {
        messageMapper.requeueStale(LocalDateTime.now().minusMinutes(10));
        messageMapper.listQueued(100).forEach(message -> executor.execute(() -> execute(message.getId())));
    }

    private KnowledgeChatMessage findQueuedTask(Long messageId) {
        return messageMapper.getTaskById(messageId);
    }

    private void dispatchAfterCommit(Long messageId) {
        Runnable dispatch = () -> executor.execute(() -> execute(messageId));
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    private KnowledgeChatSession requireSession(Long sessionId, Long userId) {
        KnowledgeChatSession session = sessionMapper.getActive(sessionId,userId);
        if(session == null) {
            throw new BaseException("会话不存在");
        }
        return session;
    }

    private List<Long> normalizeSpaces(List<Long> values) {
        Set<Long> spaces = new LinkedHashSet<>();
        if(values != null) values.stream().filter(id -> id != null && id > 0).forEach(spaces::add);
        if(spaces.isEmpty() || spaces.size() > 5) throw new BaseException("请选择 1 到 5 个知识库");
        return List.copyOf(spaces);
    }

    private String normalizeRequestKey(String requestKey) {
        String key = requestKey == null || requestKey.isBlank() ? UUID.randomUUID().toString() : requestKey.trim();
        if(key.length() > 100 || !key.matches("^[A-Za-z0-9._:-]+$")) throw new BaseException("幂等键格式不正确");
        return key;
    }

    private String writeRequest(KnowledgeChatQueryCreateDTO dto) {
        try { return objectMapper.writeValueAsString(dto); }
        catch(JsonProcessingException exception) { throw new BaseException("问答请求无法持久化"); }
    }

    private String errorSummary(Exception exception) {
        String message = exception.getMessage();
        if(message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 900 ? message.substring(0,900) : message;
    }

    private KnowledgeChatMessageVO toVO(KnowledgeChatMessage message) {
        KnowledgeChatMessageVO vo = new KnowledgeChatMessageVO();
        vo.setId(message.getId()); vo.setSessionId(message.getSessionId()); vo.setRole(message.getRole());
        vo.setContent(message.getContent()); vo.setCitationsJson(message.getCitationsJson());
        vo.setTaskStatus(message.getTaskStatus()); vo.setErrorMessage(message.getErrorMessage());
        vo.setRetryCount(message.getRetryCount()); vo.setCreatetime(message.getCreatetime()); vo.setUpdatetime(message.getUpdatetime());
        return vo;
    }
}
