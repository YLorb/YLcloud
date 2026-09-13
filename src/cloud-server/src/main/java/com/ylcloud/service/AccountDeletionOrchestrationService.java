package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.AccountDeletionJob;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.AccountDeletionJobMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserApiKeyMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.service.memory.UserMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号删除编排服务。
 * 可重入步骤：取消会话 → 删除消息 → 清理记忆 → 清理文件 → 清理向量
 *           → 吊销API Key → 删除Webhook → 清除PII → 标记PURGED
 * 复用 cleanup、memory、chat/workflow 取消与资源版本栅栏。
 * 所有步骤必须在 MARK_PURGED 前完成，不满足"个人数据按清单清除"要求不得标记 PURGED。
 */
@Service
@Slf4j
public class AccountDeletionOrchestrationService {
    private static final String TASK_DOMAIN = "maintenance";
    private static final String TASK_TYPE = "ACCOUNT_DELETION";
    private final AccountDeletionJobMapper jobMapper;
    private final UserLifecycleMapper userLifecycleMapper;
    private final UnifiedTaskCenterService taskCenter;
    private final UserMemoryService memoryService;
    private final UserApiKeyMapper apiKeyMapper;
    private final WebhookMapper webhookMapper;
    private final KnowledgeChatSessionService chatSessionService;
    private final KnowledgeChatMessageMapper messageMapper;
    private final AccountDeletionDataCleanupService dataCleanupService;
    private final SecurityAuditService auditService;
    private final ObjectMapper objectMapper;

    public AccountDeletionOrchestrationService(AccountDeletionJobMapper jobMapper,
                                               UserLifecycleMapper userLifecycleMapper,
                                               UnifiedTaskCenterService taskCenter,
                                               UserMemoryService memoryService,
                                               UserApiKeyMapper apiKeyMapper,
                                               WebhookMapper webhookMapper,
                                               KnowledgeChatSessionService chatSessionService,
                                               KnowledgeChatMessageMapper messageMapper,
                                               AccountDeletionDataCleanupService dataCleanupService,
                                               SecurityAuditService auditService,
                                               ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.userLifecycleMapper = userLifecycleMapper;
        this.taskCenter = taskCenter;
        this.memoryService = memoryService;
        this.apiKeyMapper = apiKeyMapper;
        this.webhookMapper = webhookMapper;
        this.chatSessionService = chatSessionService;
        this.messageMapper = messageMapper;
        this.dataCleanupService = dataCleanupService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交删除任务（幂等）。
     */
    @Transactional
    public AccountDeletionJob submitDeletionJob(Long userId) {
        AccountDeletionJob existing = jobMapper.getByUserId(userId);
        if (existing != null && !"FAILED".equals(existing.getStatus())) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        String jobKey = "account-deletion:" + userId;

        AccountDeletionJob job = new AccountDeletionJob();
        job.setUserId(userId);
        job.setJobKey(jobKey);
        job.setStatus("PENDING");
        job.setCurrentStep("INIT");
        job.setRetryCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        if (existing != null) {
            job.setId(existing.getId());
            jobMapper.update(job);
        } else {
            jobMapper.insert(job);
        }

        TaskCreateCommand command = new TaskCreateCommand(
                jobKey,
                TASK_DOMAIN,
                TASK_TYPE,
                new DomainTaskPayload(userId, null, Map.of("jobId", job.getId())),
                userId,
                null,
                jobKey,
                1L,
                null,
                5
        );

        UnifiedAsyncTask task = taskCenter.createTask(command);
        job.setAsyncTaskId(task.getId());
        jobMapper.update(job);

        log.info("Account deletion job submitted: userId={}, jobId={}, taskId={}", userId, job.getId(), task.getId());
        return job;
    }

    /**
     * 执行删除编排（由异步任务处理器调用）。
     * 可重入：根据 currentStep 从断点继续。
     */
    public Map<String, Object> executeDeletion(Long jobId, Long asyncTaskId, TaskExecutionContext context) throws Exception {
        AccountDeletionJob job = jobMapper.getById(jobId);
        if (job == null) throw new BaseException("删除任务不存在");
        if ("COMPLETED".equals(job.getStatus())) return Map.of("alreadyCompleted", true);
        if (!asyncTaskId.equals(job.getAsyncTaskId())) {
            throw new BaseException("任务版本不匹配，可能已被新任务替代");
        }

        Long userId = job.getUserId();
        String currentStep = job.getCurrentStep() == null ? "INIT" : job.getCurrentStep();
        Map<String, Object> results = restoreResults(job.getStepResultJson());

        try {
            // 步骤1: 取消所有活跃会话（保留消息用于后续删除步骤）
            if (shouldExecuteStep(currentStep, "CANCEL_SESSIONS")) {
                context.checkpoint();
                int cancelled = chatSessionService.cancelAllUserSessions(userId);
                results.put("cancelledSessions", cancelled);
                advanceStep(job, "CANCEL_SESSIONS", results);
            }

            // 步骤2: 删除所有个人聊天消息
            if (shouldExecuteStep(currentStep, "DELETE_MESSAGES")) {
                context.checkpoint();
                int deleted = messageMapper.redactByUserId(userId);
                results.put("deletedMessages", deleted);
                log.info("Deleted {} chat messages for userId={}", deleted, userId);
                advanceStep(job, "DELETE_MESSAGES", results);
            }

            // 步骤3: 清理用户记忆
            if (shouldExecuteStep(currentStep, "CLEAR_MEMORY")) {
                context.checkpoint();
                memoryService.clear(userId);
                results.put("memoryCleared", true);
                advanceStep(job, "CLEAR_MEMORY", results);
            }

            // 步骤4: 清理个人文件 — 查询用户所有文件并提交物理清理
            if (shouldExecuteStep(currentStep, "CLEANUP_FILES")) {
                context.checkpoint();
                Map<String, Object> fileResult = dataCleanupService.releasePersonalReferences(userId);
                results.putAll(fileResult);
                advanceStep(job, "CLEANUP_FILES", results);
            }

            // 步骤5: 仅清理个人空间向量。团队空间内容由团队继续持有。
            if (shouldExecuteStep(currentStep, "CLEANUP_VECTORS")) {
                context.checkpoint();
                Long personalSpaceId = longResult(results, "personalSpaceId");
                dataCleanupService.cleanupPersonalVectors(personalSpaceId);
                results.put("personalVectorsCleaned", personalSpaceId != null && personalSpaceId > 0);
                advanceStep(job, "CLEANUP_VECTORS", results);
            }

            // 步骤6: 验证异步副作用均已完成，再允许进入不可逆的 PII/PURGED 终态。
            if (shouldExecuteStep(currentStep, "VERIFY_CLEANUP")) {
                context.checkpoint();
                dataCleanupService.verifyPhysicalCleanup(stringListResult(results, "queuedFileUuids"));
                if (memoryService.countDeletePending(userId) > 0) {
                    throw new com.ylcloud.async.task.RetryableTaskException("用户记忆向量清理尚未完成");
                }
                memoryService.redactDeleted(userId);
                dataCleanupService.finalizePersonalSpace(userId, longResult(results, "personalSpaceId"));
                results.put("cleanupVerified", true);
                advanceStep(job, "VERIFY_CLEANUP", results);
            }

            // 步骤7: 吊销 API Key
            if (shouldExecuteStep(currentStep, "REVOKE_API_KEYS")) {
                context.checkpoint();
                int revoked = apiKeyMapper.revokeAllByUserId(userId);
                results.put("revokedApiKeys", revoked);
                advanceStep(job, "REVOKE_API_KEYS", results);
            }

            // 步骤8: 删除 Webhook 订阅
            if (shouldExecuteStep(currentStep, "DELETE_WEBHOOKS")) {
                context.checkpoint();
                int deleted = webhookMapper.deleteByUserId(userId);
                results.put("deletedWebhooks", deleted);
                advanceStep(job, "DELETE_WEBHOOKS", results);
            }

            // 步骤9: 清除 PII 数据 — 将用户个人信息匿名化
            if (shouldExecuteStep(currentStep, "CLEAR_PII")) {
                context.checkpoint();
                int piiCleared = userLifecycleMapper.clearPii(userId, LocalDateTime.now());
                if (piiCleared != 1) {
                    throw new BaseException("清除 PII 失败，账号状态可能已变化");
                }
                results.put("piiCleared", true);
                advanceStep(job, "CLEAR_PII", results);
            }

            // 步骤10: 标记为 PURGED — 仅当所有清理步骤验证完成后执行
            if (shouldExecuteStep(currentStep, "MARK_PURGED")) {
                context.checkpoint();

                if (!Boolean.TRUE.equals(results.get("cleanupVerified"))) {
                    throw new BaseException("清理结果尚未验证，拒绝标记 PURGED");
                }

                LocalDateTime now = LocalDateTime.now();
                if (userLifecycleMapper.markPurged(userId, now, now) != 1) {
                    throw new BaseException("标记 PURGED 失败，账号状态可能已变化");
                }
                results.put("markedPurged", true);
                advanceStep(job, "MARK_PURGED", results);
            }

            // 完成
            jobMapper.markCompleted(job.getId(), LocalDateTime.now());
            auditService.record(new SecurityAuditService.AuditEventBuilder()
                    .eventType("ACCOUNT_DELETION")
                    .action("PURGE_COMPLETED")
                    .subject(userId, "system")
                    .target("ACCOUNT", String.valueOf(userId), "user-" + userId)
                    .result("SUCCESS")
                    .detail(results));

            log.info("Account deletion completed: userId={}, jobId={}", userId, jobId);
            return results;

        } catch (Exception e) {
            log.error("Account deletion failed: userId={}, jobId={}, step={}", userId, jobId, currentStep, e);
            jobMapper.markFailed(job.getId(), truncate(e.getMessage()), LocalDateTime.now());

            auditService.recordFailure("ACCOUNT_DELETION", "PURGE_FAILED", userId, "system",
                    "ACCOUNT", String.valueOf(userId), "user-" + userId,
                    truncate(e.getMessage()), Map.of("currentStep", currentStep));

            throw e;
        }
    }

    private boolean shouldExecuteStep(String currentStep, String targetStep) {
        String[] steps = {"INIT", "CANCEL_SESSIONS", "DELETE_MESSAGES", "CLEAR_MEMORY",
                "CLEANUP_FILES", "CLEANUP_VECTORS", "VERIFY_CLEANUP", "REVOKE_API_KEYS", "DELETE_WEBHOOKS",
                "CLEAR_PII", "MARK_PURGED", "DONE"};

        int currentIndex = -1;
        int targetIndex = -1;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].equals(currentStep)) currentIndex = i;
            if (steps[i].equals(targetStep)) targetIndex = i;
        }

        return targetIndex > currentIndex;
    }

    private Map<String, Object> restoreResults(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new BaseException("删除任务断点数据损坏，拒绝继续");
        }
    }

    private Long longResult(Map<String, Object> results, String key) {
        Object value = results.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private List<String> stringListResult(Map<String, Object> results, String key) {
        Object value = results.get(key);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private void advanceStep(AccountDeletionJob job, String step, Map<String, Object> results) {
        job.setCurrentStep(step);
        try {
            job.setStepResultJson(objectMapper.writeValueAsString(results));
        } catch (Exception e) {
            log.warn("Failed to serialize step result", e);
        }
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.update(job);
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
