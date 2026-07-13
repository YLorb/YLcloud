package com.ylcloud.service;

import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.FileRagParseResultMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.PhysicalFileCleanupTaskMapper;
import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * 最后一个业务引用被永久删除后的物理文件清理 outbox。
 */
@Service
@Slf4j
public class PhysicalFileCleanupService {
    private final PhysicalFileCleanupTaskMapper cleanupTaskMapper;
    private final FileInfoMapper fileInfoMapper;
    private final FileVersionMapper fileVersionMapper;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final FileRagParseResultMapper parseResultMapper;
    private final MinioclientUtil minioclientUtil;
    private final ApplicationEventPublisher eventPublisher;

    public PhysicalFileCleanupService(PhysicalFileCleanupTaskMapper cleanupTaskMapper,
                                      FileInfoMapper fileInfoMapper,
                                      FileVersionMapper fileVersionMapper,
                                      FileRagChunkMapper fileRagChunkMapper,
                                      FileRagParseResultMapper parseResultMapper,
                                      MinioclientUtil minioclientUtil,
                                      ApplicationEventPublisher eventPublisher) {
        this.cleanupTaskMapper = cleanupTaskMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.fileVersionMapper = fileVersionMapper;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.parseResultMapper = parseResultMapper;
        this.minioclientUtil = minioclientUtil;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 必须仅在引用计数已经归零的永久删除事务中调用。
     */
    public void enqueue(String fileUuid) {
        fileVersionMapper.disableByFileUuid(fileUuid);
        fileRagChunkMapper.disableByFileUuid(fileUuid);
        parseResultMapper.disableByFileUuid(fileUuid);
        fileInfoMapper.delete_fileinfo_ByfileUuid(fileUuid);
        cleanupTaskMapper.enqueue(fileUuid,LocalDateTime.now());
        publishAfterCommit(fileUuid);
    }

    public void process(String fileUuid) {
        PhysicalFileCleanupTask task = cleanupTaskMapper.getByFileUuid(fileUuid);
        if(task == null || "SUCCESS".equals(task.getTaskStatus())) {
            return;
        }
        if(cleanupTaskMapper.markRunning(task.getId(),LocalDateTime.now()) == 0) {
            return;
        }
        try {
            int removed = minioclientUtil.removeObjectAllVersions(fileUuid);
            cleanupTaskMapper.markSuccess(task.getId(),LocalDateTime.now());
            log.info("物理文件已彻底清理：fileUuid={}, versions={}",fileUuid,removed);
        } catch (Exception ex) {
            cleanupTaskMapper.markFailed(task.getId(),truncate(ex.getMessage()),LocalDateTime.now());
            log.warn("物理文件清理失败，已保留重试任务：fileUuid={}",fileUuid,ex);
        }
    }

    public void recoverInterrupted() {
        cleanupTaskMapper.recoverInterrupted(LocalDateTime.now());
    }

    public void processPending() {
        cleanupTaskMapper.listPending(100).forEach(task -> process(task.getFileUuid()));
    }

    private void publishAfterCommit(String fileUuid) {
        Runnable publish = () -> eventPublisher.publishEvent(new PhysicalFileCleanupQueuedEvent(fileUuid));
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private String truncate(String message) {
        if(message == null || message.isBlank()) return "Unknown MinIO cleanup error";
        return message.length() <= 1000 ? message : message.substring(0,1000);
    }
}
