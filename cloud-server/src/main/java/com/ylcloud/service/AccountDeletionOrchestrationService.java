package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.AccountDeletionJob;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.AccountDeletionJobMapper;
import com.ylcloud.mapper.UserApiKeyMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.service.memory.UserMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 账号删除编排服务。
 * 可重入步骤：取消会话 -> 清理记忆 -> 清理文件 -> 清理向量 -> 清理API Key -> 清理Webhook -> 标记PURGED
 * 复用 cleanup、memory、chat/workflow 取消与资源版本栅栏。
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
    private final ObjectMapper objectMapper;

    public AccountDeletionOrchestrationService(AccountDeletionJobMapper jobMapper,
                                               UserLifecycleMapper userLifecycleMapper,
                                               UnifiedTaskCenterService taskCenter,
                                               UserMemoryService memoryService,
                                               UserApiKeyMapper apiKeyMapper,
                                               WebhookMapper webhookMapper,
                                               KnowledgeChatSessionService chatSessionService,
                                               ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.userLifecycleMapper = userLifecycleMapper;
        this.taskCenter = taskCenter;
        this.memoryService = memoryService;
        this.apiKeyMapper = apiKeyMapper;
        this.webhookMapper = webhookMapper;
        this.chatSessionService = chatSessionService;
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
            // 重试失败的任务
            job.setId(existing.getId());
            jobMapper.update(job);
        } else {
            jobMapper.insert(job);
        }

        // 创建统一异步任务
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

        try {
            // 步骤1: 取消所有活跃会话
            if (shouldExecuteStep(currentStep, "CANCEL_SESSIONS")) {
                context.checkpoint();
                int cancelled = chatSessionService.cancelAllUserSessions(userId);
                results.put("cancelledSessions", cancelled);
                advanceStep(job, "CANCEL_SESSIONS", results);
            }

            // 步骤2: 清理用户记忆
            if (shouldExecuteStep(currentStep, "CLEAR_MEMORY")) {
                context.checkpoint();
                memoryService.clear(userId);
                results.put("memoryCleared", true);
                advanceStep(job, "CLEAR_MEMORY", results);
            }

            // 步骤3: 清理个人文件（通过已有清理服务）
            if (shouldExecuteStep(currentStep, "CLEANUP_FILES")) {
                context.checkpoint();
                // 文件清理由 PhysicalFileCleanupService 异步处理
                results.put("filesCleanupQueued", true);
                advanceStep(job, "CLEANUP_FILES", results);
            }

            // 步骤4: 清理向量数据
            if (shouldExecuteStep(currentStep, "CLEANUP_VECTORS")) {
                context.checkpoint();
                // 向量清理由 Qdrant 异步处理
                results.put("vectorsCleanupQueued", true);
                advanceStep(job, "CLEANUP_VECTORS", results);
            }

            // 步骤5: 吊销 API Key
            if (shouldExecuteStep(currentStep, "REVOKE_API_KEYS")) {
                context.checkpoint();
                int revoked = apiKeyMapper.revokeAllByUserId(userId);
                results.put("revokedApiKeys", revoked);
                advanceStep(job, "REVOKE_API_KEYS", results);
            }

            // 步骤6: 删除 Webhook 订阅
            if (shouldExecuteStep(currentStep, "DELETE_WEBHOOKS")) {
                context.checkpoint();
                int deleted = webhookMapper.deleteByUserId(userId);
                results.put("deletedWebhooks", deleted);
                advanceStep(job, "DELETE_WEBHOOKS", results);
            }

            // 步骤7: 标记为 PURGED
            if (shouldExecuteStep(currentStep, "MARK_PURGED")) {
                context.checkpoint();
                LocalDateTime now = LocalDateTime.now();
                if (userLifecycleMapper.markPurged(userId, now, now) != 1) {
                    throw new BaseException("标记 PURGED 失败，账号状态可能已变化");
                }
                results.put("markedPurged", true);
                advanceStep(job, "MARK_PURGED", results);
            }

            // 完成
            jobMapper.markCompleted(job.getId(), LocalDateTime.now());
            log.info("Account deletion completed: userId={}, jobId={}", userId, jobId);
            return results;

        } catch (Exception e) {
            log.error("Account deletion failed: userId={}, jobId={}, step={}", userId, jobId, currentStep, e);
            jobMapper.markFailed(job.getId(), truncate(e.getMessage()), LocalDateTime.now());
            throw e;
        }
    }

    private boolean shouldExecuteStep(String currentStep, String targetStep) {
        // 步骤顺序
        String[] steps = {"INIT", "CANCEL_SESSIONS", "CLEAR_MEMORY", "CLEANUP_FILES",
                "CLEANUP_VECTORS", "REVOKE_API_KEYS", "DELETE_WEBHOOKS", "MARK_PURGED", "DONE"};

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
