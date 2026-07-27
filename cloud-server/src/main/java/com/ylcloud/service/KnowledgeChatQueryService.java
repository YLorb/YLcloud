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
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.FatalTaskException;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.StaleWorkerException;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.workflow.client.WorkflowMessageLifecycleService;
import com.ylcloud.workflow.client.WorkflowStatusPresentation;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class KnowledgeChatQueryService {
    private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final SpacePermissionService spacePermissionService;
    private final KnowledgeRagQueryService ragQueryService;
    private final ConversationContextService contextService;
    private final com.ylcloud.service.memory.UserMemoryExtractionService memoryExtractionService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final WorkflowMessageLifecycleService workflowLifecycle;
    private WebhookEventService webhookEventService;
    private QuotaService quotaService;
    private UnifiedTaskCenterService taskCenter;
    private AsyncMqProperties mqProperties;

    @Autowired
    public KnowledgeChatQueryService(KnowledgeChatSessionMapper sessionMapper,
                                     KnowledgeChatMessageMapper messageMapper,
                                     SpacePermissionService spacePermissionService,
                                     KnowledgeRagQueryService ragQueryService,
                                     ConversationContextService contextService,
                                     com.ylcloud.service.memory.UserMemoryExtractionService memoryExtractionService,
                                     ObjectMapper objectMapper,
                                     @Qualifier("ragTaskExecutor") Executor executor,
                                     WorkflowMessageLifecycleService workflowLifecycle) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.spacePermissionService = spacePermissionService;
        this.ragQueryService = ragQueryService;
        this.contextService = contextService;
        this.memoryExtractionService = memoryExtractionService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.workflowLifecycle = workflowLifecycle;
    }

    KnowledgeChatQueryService(KnowledgeChatSessionMapper sessionMapper, KnowledgeChatMessageMapper messageMapper,
                              SpacePermissionService spacePermissionService, KnowledgeRagQueryService ragQueryService,
                              ConversationContextService contextService, ObjectMapper objectMapper, Executor executor) {
        this(sessionMapper,messageMapper,spacePermissionService,ragQueryService,contextService,null,
                objectMapper,executor,null);
    }

    @Transactional
    public KnowledgeChatMessageVO submit(Long sessionId, Long userId, KnowledgeChatQueryCreateDTO dto, String requestKey) {
        KnowledgeChatSession session = requireSessionForUpdate(sessionId,userId);
        List<Long> spaceIds = normalizeSpaces(dto.getSpaceIds());
        spaceIds.forEach(spaceId -> spacePermissionService.requireMember(spaceId,userId));
        String normalizedKey = normalizeRequestKey(requestKey);
        KnowledgeChatMessage existing = messageMapper.getByRequestKey(sessionId,normalizedKey);
        if(existing != null) {
            return toVO(existing);
        }
        if(quotaService != null) quotaService.reserveAgentTask(userId);

        LocalDateTime now = LocalDateTime.now();
        long firstSequence = session.getNextSequenceNo() == null ? 1 : session.getNextSequenceNo();
        if(sessionMapper.reserveSequences(sessionId,userId,2,now) == 0) throw new BaseException("会话序号分配失败");
        KnowledgeChatMessage userMessage = new KnowledgeChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setUserId(userId);
        userMessage.setSequenceNo(firstSequence);
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
        assistant.setSequenceNo(firstSequence + 1);
        assistant.setSourceMessageId(userMessage.getId());
        assistant.setRole("assistant");
        assistant.setContent("正在生成回答…");
        assistant.setTaskStatus("QUEUED");
        assistant.setRequestKey(normalizedKey);
        assistant.setRequestJson(writeRequest(dto));
        assistant.setRetryCount(0);
        assistant.setAsyncVersion(1L);
        assistant.setStatus(StatusConstant.ENABLE);
        assistant.setCreatetime(now);
        assistant.setUpdatetime(now);
        messageMapper.insert(assistant);
        sessionMapper.touch(session.getId(),userId,now);
        if(mqChatEnabled()) registerUnified(assistant,workflowEnabled() ? "CHAT_WORKFLOW_RUN" : "CHAT_QUERY");
        else dispatchAfterCommit(assistant.getId());
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
        boolean workflowRetry = workflowLifecycle != null && workflowLifecycle.isEnabled()
                && message.getWorkflowRunId() != null;
        int updated = workflowRetry ? messageMapper.prepareWorkflowRetry(messageId) : messageMapper.retryFailed(messageId);
        if(updated == 0) {
            throw new BaseException("回答状态已变化，请刷新后重试");
        }
        long nextVersion=(message.getAsyncVersion()==null ? 1L : message.getAsyncVersion())+1L;
        message.setAsyncVersion(nextVersion);
        message.setAsyncTaskId(null);
        message.setAsyncTaskType(null);
        if(mqChatEnabled()) registerUnified(message,workflowRetry ? "CHAT_WORKFLOW_RETRY" : "CHAT_QUERY");
        else dispatchAfterCommit(messageId, workflowRetry);
        message.setTaskStatus("QUEUED");
        message.setErrorMessage(null);
        message.setRetryCount((message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1);
        message.setUpdatetime(LocalDateTime.now());
        return toVO(message);
    }

    @Transactional
    public KnowledgeChatMessageVO cancel(Long sessionId, Long messageId, Long userId) {
        requireSession(sessionId,userId);
        KnowledgeChatMessage message = messageMapper.getOwned(messageId,sessionId,userId);
        if(mqChatEnabled()) {
            if(message==null || !"assistant".equals(message.getRole())) throw new BaseException("待取消的回答不存在");
            if(!"QUEUED".equals(message.getTaskStatus()) && !"RUNNING".equals(message.getTaskStatus())) {
                throw new BaseException("当前回答不可取消");
            }
            if(message.getAsyncTaskId()==null) throw new BaseException("回答尚未绑定统一任务");
            taskCenter.cancel(message.getAsyncTaskId(),userId,"用户取消回答");
            if(workflowEnabled() && message.getWorkflowRunId()!=null) workflowLifecycle.cancel(message);
            messageMapper.cancelChatAsync(messageId,currentVersion(message),message.getAsyncTaskId(),LocalDateTime.now());
            return toVO(messageMapper.getOwned(messageId,sessionId,userId));
        }
        if(message == null || !"assistant".equals(message.getRole())) throw new BaseException("待取消的回答不存在");
        if(workflowLifecycle == null || !workflowLifecycle.isEnabled()) throw new BaseException("Workflow 未启用");
        if(!"QUEUED".equals(message.getTaskStatus()) && !"RUNNING".equals(message.getTaskStatus())) {
            throw new BaseException("当前回答不可取消");
        }
        workflowLifecycle.cancel(message);
        KnowledgeChatMessage cancelled = messageMapper.getOwned(messageId,sessionId,userId);
        return toVO(cancelled);
    }

    public void execute(Long messageId) {
        KnowledgeChatMessage before=messageMapper.getTaskById(messageId);
        if(before!=null && before.getAsyncTaskId()!=null && taskCenter!=null && taskCenter.isActive(before.getAsyncTaskId())) return;
        if(messageMapper.claimQueued(messageId) == 0) {
            return;
        }
        KnowledgeChatMessage message = findQueuedTask(messageId);
        if(message == null) {
            return;
        }
        KnowledgeChatQueryCreateDTO request = null;
        try {
            request = objectMapper.readValue(message.getRequestJson(),KnowledgeChatQueryCreateDTO.class);
            ConversationContextSnapshot context = contextService.resolve(message);
            KnowledgeRagQueryDTO ragRequest = new KnowledgeRagQueryDTO();
            ragRequest.setQuestion(request.getQuestion());
            ragRequest.setSpaceIds(request.getSpaceIds());
            ragRequest.setRetrievalMode(request.getRetrievalMode());
            ragRequest.setHistory(context.history());
            KnowledgeRagQueryVO result = ragQueryService.query(ragRequest,message.getUserId());
            if(quotaService != null) quotaService.consumeModelTokens(message.getUserId(),context.totalTokens());
            contextService.finalizeRetrieval(message,context,result.getCitations() == null ? List.of() : result.getCitations().stream()
                    .map(citation -> citation.getChunkId()).filter(java.util.Objects::nonNull).distinct().toList());
            String answer = result.getAnswer() == null || result.getAnswer().isBlank()
                    ? "当前知识库中没有足够相关的信息来回答这个问题。" : result.getAnswer().trim();
            String citations = objectMapper.writeValueAsString(result.getCitations() == null ? List.of() : result.getCitations());
            if(messageMapper.markSuccess(messageId,answer,citations) > 0) {
                if(memoryExtractionService != null) memoryExtractionService.enqueue(message);
                emitAgent(message,request,"AGENT_TASK_COMPLETED",answer);
            }
        } catch(Exception exception) {
            if(messageMapper.markFailed(messageId,errorSummary(exception)) > 0) {
                emitAgent(message,request,"AGENT_TASK_FAILED",null);
            }
        } finally {
            if(quotaService != null) quotaService.releaseAgentTask(message.getUserId());
        }
    }

    public Map<String,Object> executeAsync(Long messageId,Long asyncTaskId,long version,
                                           boolean finalAttempt,TaskExecutionContext context) {
        KnowledgeChatMessage message=requireCurrent(messageId,asyncTaskId,version);
        if(messageMapper.claimChatAsync(messageId,version,asyncTaskId,LocalDateTime.now())==0) {
            throw new StaleTaskException("Chat 任务状态已变化");
        }
        KnowledgeChatQueryCreateDTO request=null;
        try {
            context.checkpoint();
            request=objectMapper.readValue(message.getRequestJson(),KnowledgeChatQueryCreateDTO.class);
            ConversationContextSnapshot snapshot=contextService.resolve(message);
            context.checkpoint();
            KnowledgeRagQueryDTO ragRequest=new KnowledgeRagQueryDTO();
            ragRequest.setQuestion(request.getQuestion());
            ragRequest.setSpaceIds(request.getSpaceIds());
            ragRequest.setRetrievalMode(request.getRetrievalMode());
            ragRequest.setHistory(snapshot.history());
            KnowledgeRagQueryVO result=ragQueryService.query(ragRequest,message.getUserId());
            context.checkpoint();
            requireCurrent(messageId,asyncTaskId,version);
            if(quotaService!=null) quotaService.consumeModelTokens(message.getUserId(),snapshot.totalTokens());
            contextService.finalizeRetrieval(message,snapshot,result.getCitations()==null ? List.of() : result.getCitations().stream()
                    .map(citation -> citation.getChunkId()).filter(java.util.Objects::nonNull).distinct().toList());
            String answer=result.getAnswer()==null || result.getAnswer().isBlank()
                    ? "当前知识库中没有足够相关的信息来回答这个问题。" : result.getAnswer().trim();
            String citations=objectMapper.writeValueAsString(result.getCitations()==null ? List.of() : result.getCitations());
            if(messageMapper.markChatSuccessAsync(messageId,version,asyncTaskId,answer,citations,LocalDateTime.now())!=1) {
                throw new StaleTaskException("Chat 结果被版本栅栏拒绝");
            }
            if(memoryExtractionService!=null) memoryExtractionService.enqueue(message);
            emitAgent(message,request,"AGENT_TASK_COMPLETED",answer);
            return Map.of("messageId",messageId,"status","SUCCESS");
        } catch(TaskCanceledException | StaleWorkerException | StaleTaskException control) {
            throw control;
        } catch(BaseException deterministic) {
            messageMapper.markChatFailedAsync(messageId,version,asyncTaskId,"Chat request rejected",LocalDateTime.now());
            emitAgent(message,request,"AGENT_TASK_FAILED",null);
            throw new FatalTaskException("Chat 请求无法执行");
        } catch(Exception failure) {
            if(finalAttempt) {
                messageMapper.markChatFailedAsync(messageId,version,asyncTaskId,"Chat service unavailable",LocalDateTime.now());
                emitAgent(message,request,"AGENT_TASK_FAILED",null);
            }
            throw new RetryableTaskException("Chat 服务暂时不可用");
        } finally {
            if(quotaService!=null) quotaService.releaseAgentTask(message.getUserId());
        }
    }

    @Autowired(required = false)
    public void setWebhookEventService(WebhookEventService webhookEventService) {
        this.webhookEventService = webhookEventService;
    }

    @Autowired(required=false)
    public void setQuotaService(QuotaService quotaService) { this.quotaService=quotaService; }

    @Autowired(required=false)
    public void setUnifiedTaskCenter(UnifiedTaskCenterService taskCenter,AsyncMqProperties mqProperties) {
        this.taskCenter=taskCenter;
        this.mqProperties=mqProperties;
    }

    private void emitAgent(KnowledgeChatMessage message,KnowledgeChatQueryCreateDTO request,String eventType,String answer) {
        if(webhookEventService == null || message == null) return;
        try {
            KnowledgeChatQueryCreateDTO effective = request == null
                    ? objectMapper.readValue(message.getRequestJson(),KnowledgeChatQueryCreateDTO.class) : request;
            if(effective.getApiKeyId() == null || effective.getSpaceIds() == null || effective.getSpaceIds().isEmpty()) return;
            Long spaceId = effective.getSpaceIds().get(0);
            long version = Math.max(1,(message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1L);
            webhookEventService.publish(message.getUserId(),eventType,"AGENT_TASK",String.valueOf(message.getId()),
                    version,null,spaceId,Map.of("messageId",message.getId(),"sessionId",message.getSessionId(),
                            "status",eventType.endsWith("COMPLETED") ? "COMPLETED" : "FAILED"),
                    answer == null ? Map.of() : Map.of("answer",answer));
        } catch(Exception ignored) {
            // 主回答状态已经落库；Webhook Outbox 可由后续对账补偿，不能反向改写回答状态。
        }
    }

    @Scheduled(fixedDelayString = "${ylcloud.chat.recovery-delay-ms:30000}",
            initialDelayString = "${ylcloud.chat.recovery-initial-delay-ms:15000}")
    public void recoverPendingQueries() {
        if(workflowLifecycle != null && workflowLifecycle.isEnabled()) return;
        if(mqChatEnabled()) {
            messageMapper.listQueued(100).forEach(message -> {
                if(message.getAsyncTaskId()==null || !taskCenter.isActive(message.getAsyncTaskId())) {
                    registerUnified(message,"CHAT_QUERY");
                }
            });
            return;
        }
        messageMapper.requeueStale(LocalDateTime.now().minusMinutes(10));
        messageMapper.listQueued(100).forEach(message -> executor.execute(() -> execute(message.getId())));
    }

    @Scheduled(fixedDelayString = "${ylcloud.webhook.agent-reconcile-delay-ms:30000}",
            initialDelayString = "${ylcloud.webhook.agent-reconcile-initial-delay-ms:15000}")
    public void reconcileAgentWebhookEvents() {
        if(webhookEventService == null) return;
        messageMapper.listMissingAgentWebhookEvents(100).forEach(message -> emitAgent(message,null,
                "SUCCESS".equals(message.getTaskStatus()) ? "AGENT_TASK_COMPLETED" : "AGENT_TASK_FAILED",
                "SUCCESS".equals(message.getTaskStatus()) ? message.getContent() : null));
    }

    private KnowledgeChatMessage findQueuedTask(Long messageId) {
        return messageMapper.getTaskById(messageId);
    }

    public UnifiedAsyncTask registerUnified(KnowledgeChatMessage message,String taskType) {
        long version=currentVersion(message);
        UnifiedAsyncTask task=taskCenter.createTask(new TaskCreateCommand(
                "chat-message:"+message.getId()+":"+taskType+":"+version,
                "chat",taskType,new DomainTaskPayload(message.getId()),message.getUserId(),null,
                "chat-message:"+message.getSessionId()+":"+message.getId(),version));
        if(messageMapper.bindAsyncTask(message.getId(),version,task.getId(),taskType,LocalDateTime.now())!=1) {
            throw new StaleTaskException("Chat 任务绑定被版本栅栏拒绝");
        }
        message.setAsyncTaskId(task.getId());
        message.setAsyncTaskType(taskType);
        return task;
    }

    private KnowledgeChatMessage requireCurrent(Long messageId,Long asyncTaskId,long version) {
        KnowledgeChatMessage current=messageMapper.getTaskById(messageId);
        if(current==null || current.getStatus()==null || current.getStatus()!=StatusConstant.ENABLE
                || !asyncTaskId.equals(current.getAsyncTaskId()) || currentVersion(current)!=version) {
            throw new StaleTaskException("Chat 消息已删除或资源版本已变化");
        }
        return current;
    }

    private long currentVersion(KnowledgeChatMessage message) {
        return message.getAsyncVersion()==null ? 1L : message.getAsyncVersion();
    }

    private boolean workflowEnabled() {
        return workflowLifecycle!=null && workflowLifecycle.isEnabled();
    }

    private boolean mqChatEnabled() {
        return taskCenter!=null && mqProperties!=null && mqProperties.isEnabled() && mqProperties.isChat();
    }

    private void dispatchAfterCommit(Long messageId) {
        boolean workflow = workflowLifecycle != null && workflowLifecycle.isEnabled();
        dispatchAfterCommit(messageId, workflow);
    }

    private void dispatchAfterCommit(Long messageId, boolean workflowRetry) {
        Runnable action = workflowRetry
                ? () -> workflowLifecycle.retry(messageId)
                : workflowLifecycle != null && workflowLifecycle.isEnabled()
                    ? () -> workflowLifecycle.start(messageId)
                    : () -> execute(messageId);
        Runnable dispatch = () -> executor.execute(action);
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

    private KnowledgeChatSession requireSessionForUpdate(Long sessionId, Long userId) {
        KnowledgeChatSession session = sessionMapper.getActiveForUpdate(sessionId,userId);
        if(session == null) throw new BaseException("会话不存在");
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
        vo.setId(message.getId()); vo.setSessionId(message.getSessionId()); vo.setSequenceNo(message.getSequenceNo()); vo.setRole(message.getRole());
        vo.setContent(message.getContent()); vo.setCitationsJson(message.getCitationsJson());
        vo.setTaskStatus(message.getTaskStatus()); vo.setErrorMessage(message.getErrorMessage());
        vo.setRetryCount(message.getRetryCount());
        applyWorkflowPresentation(vo,message);
        vo.setCreatetime(message.getCreatetime()); vo.setUpdatetime(message.getUpdatetime());
        return vo;
    }

    static void applyWorkflowPresentation(KnowledgeChatMessageVO vo, KnowledgeChatMessage message) {
        vo.setWorkflowRunId(message.getWorkflowRunId());
        vo.setWorkflowExecutionId(message.getWorkflowExecutionId());
        vo.setWorkflowExecutionEpoch(message.getWorkflowExecutionEpoch());
        vo.setWorkflowStatus(message.getWorkflowStatus());
        vo.setDegraded(Boolean.TRUE.equals(message.getWorkflowDegraded()));
        if(message.getWorkflowStatus() != null) {
            try {
                WorkflowStatusPresentation presentation = WorkflowStatusPresentation.from(
                        WorkflowRunStatus.valueOf(message.getWorkflowStatus()));
                vo.setStatusColor(presentation.color());
            } catch(IllegalArgumentException ignored) {
                vo.setStatusColor(null);
            }
        }
    }
}
