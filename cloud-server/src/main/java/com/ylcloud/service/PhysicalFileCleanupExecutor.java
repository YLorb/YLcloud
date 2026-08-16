package com.ylcloud.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ylcloud.async.mq.AsyncMqProperties;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 事务提交后异步执行清理，并在应用启动时恢复未完成任务。
 */
@Component
public class PhysicalFileCleanupExecutor {
    private final PhysicalFileCleanupService cleanupService;
    private AsyncMqProperties mqProperties;

    public PhysicalFileCleanupExecutor(PhysicalFileCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Async("ragTaskExecutor")
    @EventListener
    public void onCleanupQueued(PhysicalFileCleanupQueuedEvent event) {
        if(mqEnabled()) return;
        cleanupService.process(event.fileUuid());
    }

    @Async("ragTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingCleanup() {
        if(mqEnabled()) { cleanupService.recoverInterrupted(); cleanupService.enqueuePendingAsync(); return; }
        cleanupService.recoverInterrupted();
        cleanupService.processPending();
    }

    /**
     * MinIO 暂时不可用时定期重试失败的持久化清理任务。
     */
    @Scheduled(fixedDelayString = "${ylcloud.cleanup.retry-delay-ms:60000}",
            initialDelayString = "${ylcloud.cleanup.retry-initial-delay-ms:60000}")
    public void retryPendingCleanup() {
        if(mqEnabled()) { cleanupService.enqueuePendingAsync(); return; }
        cleanupService.processPending();
    }

    @Autowired(required=false)
    public void setMqProperties(AsyncMqProperties mqProperties) { this.mqProperties=mqProperties; }

    private boolean mqEnabled() { return mqProperties != null && mqProperties.isEnabled() && mqProperties.isCleanup(); }
}
