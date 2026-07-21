package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.workflow.client.WorkflowRunRequestFactory.PreparedWorkflowRun;
import com.ylcloud.workflow.contract.WorkflowContracts.JavaMessageStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunAccepted;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

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
                        message.getWorkflowExecutionEpoch(), WorkflowRunStatus.FAILED.name(), errorSummary(exception));
            }
        } catch (Exception exception) {
            messageMapper.markWorkflowFailed(messageId, message.getWorkflowExecutionId(),
                    message.getWorkflowExecutionEpoch(), WorkflowRunStatus.FAILED.name(), errorSummary(exception));
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
        messageMapper.requeueStaleWorkflowGeneration(LocalDateTime.now().minusMinutes(10));
        messageMapper.listWorkflowUnaccepted(RECOVERY_BATCH_SIZE)
                .forEach(message -> start(message.getId()));
        messageMapper.listWorkflowReconcilable(RECOVERY_BATCH_SIZE)
                .forEach(message -> reconcile(message.getId()));
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
            messageMapper.markWorkflowGenerationFailed(message.getId(), epoch, errorSummary(exception));
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
