package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import com.ylcloud.workflow.client.WorkflowRunRequestFactory.PreparedWorkflowRun;
import com.ylcloud.workflow.contract.WorkflowContracts.JavaMessageStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunAccepted;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

/**
 * 将 Java 消息与 Workflow Run/Execution 绑定，并以 execution epoch 作为所有异步写入的 CAS 门禁。
 * Workflow 只冻结可注入 Context；最终回答必须在本服务中通过 Java 模型客户端生成。
 */
@Service
public class WorkflowMessageLifecycleService {
    private static final int RECOVERY_BATCH_SIZE = 100;
    private final WorkflowHttpClient client;
    private final WorkflowRunRequestFactory requestFactory;
    private final WorkflowAnswerGenerator answerGenerator;
    private final KnowledgeChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private UnifiedTaskCenterService taskCenter;
    private AsyncMqProperties mqProperties;

    public WorkflowMessageLifecycleService(
            WorkflowHttpClient client,
            WorkflowRunRequestFactory requestFactory,
            WorkflowAnswerGenerator answerGenerator,
            KnowledgeChatMessageMapper messageMapper,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.requestFactory = requestFactory;
        this.answerGenerator = answerGenerator;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public void start(Long messageId) {
        KnowledgeChatMessage message = messageMapper.getTaskById(messageId);
        if (message == null || message.getWorkflowRunId() != null || !"QUEUED".equals(message.getTaskStatus())) {
            return;
        }
        try {
            PreparedWorkflowRun prepared = requestFactory.create(message);
            WorkflowRunAccepted accepted = client.createRun(prepared.request(), prepared.idempotencyKey());
            messageMapper.bindWorkflowRun(messageId, accepted.runId().toString(),
                    accepted.executionId().toString(), accepted.executionEpoch());
        } catch (Exception exception) {
            messageMapper.markWorkflowSubmissionFailed(messageId, errorSummary(exception));
        }
    }

    public void reconcile(Long messageId) {
        KnowledgeChatMessage message = messageMapper.getTaskById(messageId);
        if (message == null || message.getWorkflowRunId() == null) return;
        if ("PENDING".equals(message.getGenerationStatus())) {
            generateFinal(message);
            return;
        }
        if (!isWorkflowActive(message.getWorkflowStatus())) return;
        try {
            UUID runId = UUID.fromString(message.getWorkflowRunId());
            WorkflowRunStatusResponse remote = client.getRun(runId);
            if (!matchesCurrent(message, remote.runId(), remote.executionId(), remote.executionEpoch())) return;
            WorkflowStatusPresentation presentation = WorkflowStatusPresentation.from(remote.status());
            if (!remote.status().isTerminal()) {
                messageMapper.updateWorkflowProgress(messageId, remote.executionId().toString(),
                        remote.executionEpoch(), remote.status().name(), presentation.javaStatus().name(), false);
                return;
            }
            if (remote.status() != WorkflowRunStatus.SUCCEEDED && remote.status() != WorkflowRunStatus.DEGRADED) {
                messageMapper.markWorkflowFailed(messageId, remote.executionId().toString(),
                        remote.executionEpoch(), remote.status().name(), "Workflow 状态：" + remote.status().name());
                return;
            }
            WorkflowResult result = client.getResult(runId);
            if (!matchesCurrent(message, result.runId(), result.executionId(), result.executionEpoch())) return;
            if (result.status() != remote.status()) {
                throw new BaseException("Workflow 状态与结果不一致");
            }
            int queued = messageMapper.queueWorkflowGeneration(messageId, runId.toString(),
                    result.executionId().toString(), result.executionEpoch(), result.status().name(),
                    result.status() == WorkflowRunStatus.DEGRADED, result.resultHash(), result.snapshotHash(),
                    objectMapper.writeValueAsString(result));
            KnowledgeChatMessage current = queued > 0 ? messageMapper.getTaskById(messageId) : message;
            if (queued > 0) generateFinal(current);
        } catch (WorkflowClientException exception) {
            // 短暂查询失败由下一次 2 秒调和重试；确定性的 4xx/契约拒绝则终止当前轮次。
            if (!exception.isRetryable()) {
                messageMapper.markWorkflowFailed(messageId, message.getWorkflowExecutionId(),
                        message.getWorkflowExecutionEpoch(), WorkflowRunStatus.FAILED.name(), "Workflow service rejected request");
            }
        } catch (Exception exception) {
            messageMapper.markWorkflowFailed(messageId, message.getWorkflowExecutionId(),
                    message.getWorkflowExecutionEpoch(), WorkflowRunStatus.FAILED.name(), "Workflow internal processing failure");
        }
    }

    public void retry(Long messageId) {
        KnowledgeChatMessage message = messageMapper.getTaskById(messageId);
        if (message == null || message.getWorkflowRunId() == null || !"QUEUED".equals(message.getTaskStatus())) return;
        if (("SUCCEEDED".equals(message.getWorkflowStatus()) || "DEGRADED".equals(message.getWorkflowStatus()))
                && message.getWorkflowResultJson() != null) {
            generateFinal(message);
            return;
        }
        int oldEpoch = message.getWorkflowExecutionEpoch() == null ? 0 : message.getWorkflowExecutionEpoch();
        try {
            UUID runId = UUID.fromString(message.getWorkflowRunId());
            String retryKey = "assistant:" + messageId + ":retry:" + message.getRetryCount();
            WorkflowRunAccepted accepted = client.retryRun(runId, retryKey);
            if (!runId.equals(accepted.runId()) || accepted.executionEpoch() <= oldEpoch) {
                throw new BaseException("Workflow 返回了无效的重试轮次");
            }
            messageMapper.bindWorkflowRetry(messageId, runId.toString(), accepted.executionId().toString(),
                    accepted.executionEpoch());
        } catch (Exception exception) {
            messageMapper.markWorkflowRetrySubmissionFailed(messageId, oldEpoch, errorSummary(exception));
        }
    }

    public Map<String,Object> executeAsync(Long messageId,Long asyncTaskId,long version,boolean retry,
                                           boolean finalAttempt,TaskExecutionContext context) {
        KnowledgeChatMessage message=requireCurrent(messageId,asyncTaskId,version);
        if(messageMapper.claimChatAsync(messageId,version,asyncTaskId,LocalDateTime.now())==0) {
            throw new StaleTaskException("Workflow Chat 任务状态已变化");
        }
        try {
            context.checkpoint();
            message=messageMapper.getTaskById(messageId);
            if(message.getWorkflowRunId()==null) startAccepted(message);
            else if(retry && !isSuccessfulWorkflow(message)) retryAccepted(message);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
            while(System.nanoTime()<deadline) {
                context.checkpoint();
                message=requireCurrent(messageId,asyncTaskId,version);
                if("SUCCESS".equals(message.getTaskStatus())) {
                    return Map.of("messageId",messageId,"status","SUCCESS","workflowRunId",message.getWorkflowRunId());
                }
                if("CANCELED".equals(message.getTaskStatus())) throw new TaskCanceledException();
                if("FAILED".equals(message.getTaskStatus())) {
                    if("FAILED".equals(message.getGenerationStatus()) && !finalAttempt
                            && messageMapper.requeueWorkflowGenerationFailure(messageId,message.getWorkflowExecutionEpoch())==1) {
                        throw new RetryableTaskException("Workflow 最终回答生成暂时不可用");
                    }
                    throw new FatalTaskException("Workflow 执行失败");
                }
                reconcile(messageId);
                message=messageMapper.getTaskById(messageId);
                if(message!=null && "SUCCESS".equals(message.getTaskStatus())) {
                    return Map.of("messageId",messageId,"status","SUCCESS","workflowRunId",message.getWorkflowRunId());
                }
                try { Thread.sleep(2000L); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetryableTaskException("Workflow 等待被中断");
                }
            }
            if(finalAttempt) failAsyncDomain(messageId,asyncTaskId,version,"Workflow processing timed out");
            throw new RetryableTaskException("Workflow 执行尚未完成");
        } catch(TaskCanceledException | StaleWorkerException | StaleTaskException | FatalTaskException control) {
            throw control;
        } catch(WorkflowClientException failure) {
            if(finalAttempt || !failure.isRetryable()) failAsyncDomain(messageId,asyncTaskId,version,"Workflow service unavailable");
            if(failure.isRetryable()) throw new RetryableTaskException("Workflow 服务暂时不可用");
            throw new FatalTaskException("Workflow 请求被拒绝");
        } catch(RetryableTaskException retryable) {
            throw retryable;
        } catch(Exception failure) {
            if(finalAttempt) failAsyncDomain(messageId,asyncTaskId,version,"Workflow internal failure");
            throw new RetryableTaskException("Workflow 服务暂时不可用");
        }
    }

    private void startAccepted(KnowledgeChatMessage message) {
        PreparedWorkflowRun prepared=requestFactory.create(message);
        WorkflowRunAccepted accepted=client.createRun(prepared.request(),prepared.idempotencyKey());
        if(messageMapper.bindWorkflowRun(message.getId(),accepted.runId().toString(),accepted.executionId().toString(),
                accepted.executionEpoch())!=1) throw new StaleTaskException("Workflow 受理结果被版本栅栏拒绝");
    }

    private void retryAccepted(KnowledgeChatMessage message) {
        int oldEpoch=message.getWorkflowExecutionEpoch()==null ? 0 : message.getWorkflowExecutionEpoch();
        UUID runId=UUID.fromString(message.getWorkflowRunId());
        WorkflowRunAccepted accepted=client.retryRun(runId,"assistant:"+message.getId()+":retry:"+message.getRetryCount());
        if(!runId.equals(accepted.runId()) || accepted.executionEpoch()<=oldEpoch) {
            throw new FatalTaskException("Workflow 返回了无效的重试轮次");
        }
        if(messageMapper.bindWorkflowRetry(message.getId(),runId.toString(),accepted.executionId().toString(),
                accepted.executionEpoch())!=1) throw new StaleTaskException("Workflow 重试结果被版本栅栏拒绝");
    }

    private boolean isSuccessfulWorkflow(KnowledgeChatMessage message) {
        return "SUCCEEDED".equals(message.getWorkflowStatus()) || "DEGRADED".equals(message.getWorkflowStatus());
    }

    private KnowledgeChatMessage requireCurrent(Long messageId,Long asyncTaskId,long version) {
        KnowledgeChatMessage current=messageMapper.getTaskById(messageId);
        long currentVersion=current==null || current.getAsyncVersion()==null ? 1L : current.getAsyncVersion();
        if(current==null || current.getStatus()==null || current.getStatus()!=1
                || !asyncTaskId.equals(current.getAsyncTaskId()) || currentVersion!=version) {
            throw new StaleTaskException("Workflow Chat 消息已删除或资源版本已变化");
        }
        return current;
    }

    private void failAsyncDomain(Long messageId,Long asyncTaskId,long version,String safeError) {
        messageMapper.markChatFailedAsync(messageId,version,asyncTaskId,safeError,LocalDateTime.now());
    }

    public void cancel(KnowledgeChatMessage message) {
        if (message.getWorkflowRunId() == null || message.getWorkflowExecutionId() == null
                || message.getWorkflowExecutionEpoch() == null) {
            throw new BaseException("回答尚未被 Workflow 受理");
        }
        WorkflowOperationResponse response = client.cancelRun(UUID.fromString(message.getWorkflowRunId()));
        if (!message.getWorkflowRunId().equals(response.runId().toString())
                || response.status() != WorkflowRunStatus.CANCELLED) {
            throw new BaseException("Workflow 取消响应不匹配");
        }
        messageMapper.markWorkflowFailed(message.getId(), message.getWorkflowExecutionId(),
                message.getWorkflowExecutionEpoch(), WorkflowRunStatus.CANCELLED.name(), "用户已取消");
    }

    @Scheduled(fixedDelayString = "${ylcloud.workflow.reconcile-delay-ms:2000}",
            initialDelayString = "${ylcloud.workflow.reconcile-initial-delay-ms:2000}")
    public void recover() {
        if (!isEnabled()) return;
        if(mqChatEnabled()) {
            messageMapper.listWorkflowUnaccepted(RECOVERY_BATCH_SIZE).forEach(message -> registerUnified(message,"CHAT_WORKFLOW_RUN"));
            messageMapper.listWorkflowReconcilable(RECOVERY_BATCH_SIZE).forEach(message -> {
                if(message.getAsyncTaskId()==null || !taskCenter.isActive(message.getAsyncTaskId())) {
                    registerUnified(message,"CHAT_WORKFLOW_RUN");
                }
            });
            return;
        }
        messageMapper.requeueStaleWorkflowGeneration(LocalDateTime.now().minusMinutes(10));
        messageMapper.listWorkflowUnaccepted(RECOVERY_BATCH_SIZE)
                .forEach(message -> start(message.getId()));
        messageMapper.listWorkflowReconcilable(RECOVERY_BATCH_SIZE)
                .forEach(message -> reconcile(message.getId()));
    }

    @Autowired(required=false)
    public void setUnifiedTaskCenter(UnifiedTaskCenterService taskCenter,AsyncMqProperties mqProperties) {
        this.taskCenter=taskCenter;
        this.mqProperties=mqProperties;
    }

    private void registerUnified(KnowledgeChatMessage message,String taskType) {
        if(message.getAsyncTaskId()!=null && taskCenter.isActive(message.getAsyncTaskId())) return;
        long version=message.getAsyncVersion()==null ? 1L : message.getAsyncVersion();
        UnifiedAsyncTask task=taskCenter.createTask(new TaskCreateCommand(
                "chat-message:"+message.getId()+":"+taskType+":"+version,"chat",taskType,
                new DomainTaskPayload(message.getId()),message.getUserId(),null,
                "chat-message:"+message.getSessionId()+":"+message.getId(),version));
        if(messageMapper.bindAsyncTask(message.getId(),version,task.getId(),taskType,LocalDateTime.now())!=1) {
            throw new StaleTaskException("Workflow Chat 任务绑定被版本栅栏拒绝");
        }
    }

    private boolean mqChatEnabled() {
        return taskCenter!=null && mqProperties!=null && mqProperties.isEnabled() && mqProperties.isChat();
    }

    private void generateFinal(KnowledgeChatMessage message) {
        Integer epoch = message.getWorkflowExecutionEpoch();
        if (epoch == null || messageMapper.claimWorkflowGeneration(message.getId(), epoch) == 0) return;
        try {
            KnowledgeChatMessage claimed = messageMapper.getTaskById(message.getId());
            WorkflowResult result = objectMapper.readValue(claimed.getWorkflowResultJson(), WorkflowResult.class);
            if (!matchesCurrent(claimed, result.runId(), result.executionId(), result.executionEpoch())) {
                throw new BaseException("Workflow 冻结结果与当前轮次不一致");
            }
            String answer = answerGenerator.generate(claimed, result);
            messageMapper.markWorkflowGenerated(message.getId(), epoch, answer);
        } catch (Exception exception) {
            messageMapper.markWorkflowGenerationFailed(message.getId(), epoch, "Workflow final generation failed");
        }
    }

    private boolean matchesCurrent(KnowledgeChatMessage message, UUID runId, UUID executionId, int epoch) {
        return runId.toString().equals(message.getWorkflowRunId())
                && executionId.toString().equals(message.getWorkflowExecutionId())
                && epoch == message.getWorkflowExecutionEpoch();
    }

    private boolean isWorkflowActive(String status) {
        if (status == null) return false;
        return switch (WorkflowRunStatus.valueOf(status)) {
            case QUEUED, PLANNING, VALIDATING, RUNNING -> true;
            default -> false;
        };
    }

    private String errorSummary(Exception exception) {
        if (!(exception instanceof BaseException) && !(exception instanceof WorkflowClientException)) {
            return "Workflow 内部处理失败";
        }
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }
}
