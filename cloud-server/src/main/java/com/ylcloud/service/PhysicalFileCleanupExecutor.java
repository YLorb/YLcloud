package com.ylcloud.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事务提交后异步执行清理，并在应用启动时恢复未完成任务。
 */
@Component
public class PhysicalFileCleanupExecutor {
    private final PhysicalFileCleanupService cleanupService;

    public PhysicalFileCleanupExecutor(PhysicalFileCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Async("ragTaskExecutor")
    @EventListener
    public void onCleanupQueued(PhysicalFileCleanupQueuedEvent event) {
        cleanupService.process(event.fileUuid());
    }

    @Async("ragTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingCleanup() {
        cleanupService.recoverInterrupted();
        cleanupService.processPending();
    }

    /**
     * MinIO 暂时不可用时定期重试失败的持久化清理任务。
     */
    @Scheduled(fixedDelayString = "${ylcloud.cleanup.retry-delay-ms:60000}",
            initialDelayString = "${ylcloud.cleanup.retry-initial-delay-ms:60000}")
    public void retryPendingCleanup() {
        cleanupService.processPending();
    }
}
