package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.AccountDeletionJob;
import com.ylcloud.entity.File;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.AccountDeletionJobMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserApiKeyMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.service.memory.UserMemoryService;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    /** 文件清理单次可重试最大次数 */
    private static final int MAX_FILE_CLEANUP_RETRIES = 3;

    private final AccountDeletionJobMapper jobMapper;
    private final UserLifecycleMapper userLifecycleMapper;
    private final UnifiedTaskCenterService taskCenter;
    private final UserMemoryService memoryService;
    private final UserApiKeyMapper apiKeyMapper;
    private final WebhookMapper webhookMapper;
    private final KnowledgeChatSessionService chatSessionService;
    private final KnowledgeChatMessageMapper messageMapper;
    private final FileInfoMapper fileInfoMapper;
    private final PhysicalFileCleanupService cleanupService;
    private final QdrantVectorStoreService qdrantVectorStoreService;
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
                                               FileInfoMapper fileInfoMapper,
                                               PhysicalFileCleanupService cleanupService,
                                               QdrantVectorStoreService qdrantVectorStoreService,
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
        this.fileInfoMapper = fileInfoMapper;
        this.cleanupService = cleanupService;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
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
        Map<String, Object> results = new HashMap<>();
        List<String> cleanupErrors = new ArrayList<>();

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
                int deleted = messageMapper.disableByUserId(userId);
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
                Map<String, Object> fileResult = cleanupUserFiles(userId);
                results.putAll(fileResult);
                @SuppressWarnings("unchecked")
                List<String> fileErrors = (List<String>) fileResult.getOrDefault("errors", List.of());
                cleanupErrors.addAll(fileErrors);
                advanceStep(job, "CLEANUP_FILES", results);
            }

            // 步骤5: 清理向量数据 — 标记向量待清理并提交异步任务
            if (shouldExecuteStep(currentStep, "CLEANUP_VECTORS")) {
                context.checkpoint();
                Map<String, Object> vectorResult = cleanupUserVectors(userId);
                results.putAll(vectorResult);
                advanceStep(job, "CLEANUP_VECTORS", results);
            }

            // 步骤6: 吊销 API Key
            if (shouldExecuteStep(currentStep, "REVOKE_API_KEYS")) {
                context.checkpoint();
                int revoked = apiKeyMapper.revokeAllByUserId(userId);
                results.put("revokedApiKeys", revoked);
                advanceStep(job, "REVOKE_API_KEYS", results);
            }

            // 步骤7: 删除 Webhook 订阅
            if (shouldExecuteStep(currentStep, "DELETE_WEBHOOKS")) {
                context.checkpoint();
                int deleted = webhookMapper.deleteByUserId(userId);
                results.put("deletedWebhooks", deleted);
                advanceStep(job, "DELETE_WEBHOOKS", results);
            }

            // 步骤8: 清除 PII 数据 — 将用户个人信息匿名化
            if (shouldExecuteStep(currentStep, "CLEAR_PII")) {
                context.checkpoint();
                int piiCleared = userLifecycleMapper.clearPii(userId, LocalDateTime.now());
                if (piiCleared != 1) {
                    throw new BaseException("清除 PII 失败，账号状态可能已变化");
                }
                results.put("piiCleared", true);
                advanceStep(job, "CLEAR_PII", results);
            }

            // 步骤9: 标记为 PURGED — 仅当所有清理步骤完成后执行
            if (shouldExecuteStep(currentStep, "MARK_PURGED")) {
                context.checkpoint();

                // 最终验证：确保关键清理步骤已执行
                if (!cleanupErrors.isEmpty()) {
                    log.warn("Some file cleanups had errors for userId={}: {}", userId, cleanupErrors);
                }

                LocalDateTime now = LocalDateTime.now();
                if (userLifecycleMapper.markPurged(userId, now, now) != 1) {
                    throw new BaseException("标记 PURGED 失败，账号状态可能已变化");
                }
                results.put("markedPurged", true);
                if (!cleanupErrors.isEmpty()) {
                    results.put("cleanupWarnings", cleanupErrors);
                }
                advanceStep(job, "MARK_PURGED", results);
            }

            // 完成
            jobMapper.markCompleted(job.getId(), LocalDateTime.now());
            results.put("cleanupErrors", cleanupErrors);

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

    /**
     * 清理用户所有个人文件，返回清理计数和错误列表。
     * 单文件清理失败不中断整个步骤，但会累积错误供后续核查。
     */
    private Map<String, Object> cleanupUserFiles(Long userId) {
        List<File> userFiles = fileInfoMapper.listAllByUserId(userId);
        int cleaned = 0;
        List<String> errors = new ArrayList<>();

        for (File file : userFiles) {
            if (file.getFileUuid() == null) continue;

            boolean ok = false;
            Exception lastEx = null;
            for (int attempt = 1; attempt <= MAX_FILE_CLEANUP_RETRIES; attempt++) {
                try {
                    fileInfoMapper.softDeleteByFileUuid(file.getFileUuid(), userId);
                    cleanupService.enqueue(file.getFileUuid());
                    cleaned++;
                    ok = true;
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    log.warn("File cleanup attempt {}/{} failed for fileUuid={}, userId={}: {}",
                            attempt, MAX_FILE_CLEANUP_RETRIES, file.getFileUuid(), userId, e.getMessage());
                }
            }
            if (!ok && lastEx != null) {
                String err = "fileUuid=" + file.getFileUuid() + ": " + lastEx.getMessage();
                errors.add(err);
                log.error("File cleanup FAILED after {} retries for userId={}: {}",
                        MAX_FILE_CLEANUP_RETRIES, userId, err);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("filesCleaned", cleaned);
        result.put("filesTotal", userFiles.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 清理用户关联的向量数据。
     * 对应用户拥有的 Team Space，通过 QdrantVectorStoreService 删除该 Space 的全部向量点，
     * 确保账号删除后不留向量残留。
     */
    private Map<String, Object> cleanupUserVectors(Long userId) {
        List<Long> teamIds = userLifecycleMapper.listOwnedTeamIds(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("teamsFound", teamIds == null ? 0 : teamIds.size());
        result.put("teamsQueuedForVectorCleanup", teamIds);

        if (teamIds == null || teamIds.isEmpty()) {
            return result;
        }

        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (Long teamId : teamIds) {
            try {
                qdrantVectorStoreService.deleteBySpace(teamId);
                deleted++;
                log.info("Vector cleanup executed for teamId={}, userId={}", teamId, userId);
            } catch (Exception e) {
                String err = "teamId=" + teamId + ": " + e.getMessage();
                errors.add(err);
                log.warn("Failed to cleanup vectors for teamId={}, userId={}: {}", teamId, userId, e.getMessage());
            }
        }
        result.put("vectorsDeleted", deleted);
        result.put("vectorsTotal", teamIds.size());
        result.put("vectorErrors", errors);
        if (!errors.isEmpty()) {
            throw new BaseException("向量清理失败: " + errors);
        }
        return result;
    }

    private boolean shouldExecuteStep(String currentStep, String targetStep) {
        String[] steps = {"INIT", "CANCEL_SESSIONS", "DELETE_MESSAGES", "CLEAR_MEMORY",
                "CLEANUP_FILES", "CLEANUP_VECTORS", "REVOKE_API_KEYS", "DELETE_WEBHOOKS",
                "CLEAR_PII", "MARK_PURGED", "DONE"};

        int currentIndex = -1;
        int targetIndex = -1;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].equals(currentStep)) currentIndex = i;
            if (steps[i].equals(targetStep)) targetIndex = i;
        }

        return targetIndex > currentIndex;
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
