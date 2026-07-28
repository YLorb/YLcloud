package com.ylcloud.service;

import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.FileRagParseResultMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.PhysicalFileCleanupTaskMapper;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.async.worker.StaleWorkerException;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.entity.UnifiedAsyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

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
    private AsyncMqProperties mqProperties;
    private UnifiedTaskCenterService taskCenter;

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
        if(mqCleanupEnabled()) {
            registerAsync(cleanupTaskMapper.getByFileUuid(fileUuid));
        } else {
            publishAfterCommit(fileUuid);
        }
    }

    public Map<String,Object> executeAsync(Long cleanupId,Long asyncTaskId,TaskExecutionContext context) {
        PhysicalFileCleanupTask task = cleanupTaskMapper.getById(cleanupId);
        if(task == null || "SUCCESS".equals(task.getTaskStatus())) return Map.of("alreadyAbsent",true);
        if(!asyncTaskId.equals(task.getAsyncTaskId())) throw new StaleTaskException("物理清理任务已被新版本替代");
        if(fileInfoMapper.getFileInfo(task.getFileUuid(),0L) != null) throw new StaleTaskException("物理文件已重新登记");
        if(!"RUNNING".equals(task.getTaskStatus())
                && cleanupTaskMapper.markRunning(task.getId(),LocalDateTime.now()) == 0) {
            throw new StaleTaskException("物理清理任务状态已变化");
        }
        try {
            context.checkpoint();
            int removed = minioclientUtil.removeObjectAllVersions(task.getFileUuid());
            context.checkpoint();
            if(cleanupTaskMapper.markAsyncSuccess(task.getId(),asyncTaskId,LocalDateTime.now()) != 1) {
                throw new StaleTaskException("物理清理结果被版本栅栏拒绝");
            }
            return Map.of("removedVersions",removed);
        } catch(TaskCanceledException | StaleWorkerException | StaleTaskException control) {
            throw control;
        } catch(Exception exception) {
            cleanupTaskMapper.markAsyncFailed(task.getId(),asyncTaskId,truncate(exception.getMessage()),LocalDateTime.now());
            log.warn("Async physical cleanup deferred: cleanupId={}",cleanupId,exception);
            throw new RetryableTaskException("物理文件存储暂时不可用");
        }
    }

    public void process(String fileUuid) {
        PhysicalFileCleanupTask task = cleanupTaskMapper.getByFileUuid(fileUuid);
        if(task == null || "SUCCESS".equals(task.getTaskStatus())) {
            return;
        }
        if(hasActiveAsyncTask(task.getAsyncTaskId())) return;
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

    @Transactional
    public void enqueuePendingAsync() {
        if(!mqCleanupEnabled()) return;
        cleanupTaskMapper.listPending(100).forEach(this::registerAsync);
    }

    private void registerAsync(PhysicalFileCleanupTask cleanup) {
        if(cleanup == null) return;
        long version = cleanup.getResourceVersion() == null ? 1 : cleanup.getResourceVersion();
        UnifiedAsyncTask task = taskCenter.createTask(new TaskCreateCommand(
                "physical-file-cleanup:" + cleanup.getId() + ":" + version,
                "cleanup","PHYSICAL_FILE_CLEANUP",new DomainTaskPayload(cleanup.getId()),null,null,
                "physical-file:" + cleanup.getFileUuid(),version));
        cleanupTaskMapper.bindAsyncTask(cleanup.getId(),version,task.getId(),LocalDateTime.now());
    }

    @Autowired(required=false)
    public void setAsyncTaskInfrastructure(AsyncMqProperties mqProperties,UnifiedTaskCenterService taskCenter) {
        this.mqProperties=mqProperties;
        this.taskCenter=taskCenter;
    }

    private boolean mqCleanupEnabled() {
        return mqProperties != null && taskCenter != null && mqProperties.isEnabled() && mqProperties.isCleanup();
    }

    private boolean hasActiveAsyncTask(Long taskId) {
        return taskCenter != null && taskCenter.isActive(taskId);
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
