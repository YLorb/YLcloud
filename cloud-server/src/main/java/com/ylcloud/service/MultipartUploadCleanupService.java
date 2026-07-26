package com.ylcloud.service;

import com.ylcloud.entity.UploadTask;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.StaleWorkerException;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.UnifiedAsyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 清理超过保留时间且没有新请求的断点续传分片。 */
@Service
@Slf4j
public class MultipartUploadCleanupService {
    private final MultifileMapper multifileMapper;
    private final ChunkUploadMapper chunkUploadMapper;
    private final MinioclientUtil minioclientUtil;
    private AsyncMqProperties mqProperties;
    private UnifiedTaskCenterService taskCenter;

    @Value("${ylcloud.upload.multipart-retention-hours:24}")
    private long retentionHours;

    @Value("${ylcloud.upload.merge-lease-seconds:600}")
    private long mergeLeaseSeconds;

    public MultipartUploadCleanupService(MultifileMapper multifileMapper,
                                         ChunkUploadMapper chunkUploadMapper,
                                         MinioclientUtil minioclientUtil) {
        this.multifileMapper = multifileMapper;
        this.chunkUploadMapper = chunkUploadMapper;
        this.minioclientUtil = minioclientUtil;
    }

    @Scheduled(fixedDelayString = "${ylcloud.upload.multipart-cleanup-delay-ms:3600000}")
    @Transactional
    public void cleanupStaleUploads() {
        multifileMapper.recoverStaleMerges(LocalDateTime.now().minusSeconds(Math.max(60,mergeLeaseSeconds)));
        if(mqCleanupEnabled()) {
            enqueueMergedUploads();
            LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
            for(UploadTask task : multifileMapper.listStale(cutoff,100)) {
                if(multifileMapper.markExpired(task.getId(),cutoff) == 1) enqueue(task,"MULTIPART_EXPIRED_CLEANUP");
            }
            return;
        }
        cleanupMergedUploads();
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        for(UploadTask task : multifileMapper.listStale(cutoff,100)) {
            if(multifileMapper.markExpired(task.getId(),cutoff) == 0) {
                continue;
            }
            try {
                int removed = minioclientUtil.removeFilePartsByFileUuidStrict(task.getFileUuid());
                log.info("已清理过期分片上传: uploadId={}, parts={}",task.getUploadId(),removed);
            } catch (Exception ex) {
                multifileMapper.markCleanupRetry(task.getId());
                log.warn("过期分片清理失败，将在后续调度重试: uploadId={}",task.getUploadId(),ex);
            }
        }
    }

    public void cleanupMergedUploads() {
        for(UploadTask task : multifileMapper.listMergedPendingCleanup(100)) {
            cleanupMergedTask(task.getId(),task.getFileUuid());
        }
    }

    public Map<String,Object> executeAsync(Long uploadTaskId,Long asyncTaskId,boolean expired,
                                           TaskExecutionContext context) {
        UploadTask task = multifileMapper.getCleanupById(uploadTaskId);
        if(task == null || task.getPartsCleanedTime() != null) return Map.of("alreadyAbsent",true);
        if(!asyncTaskId.equals(task.getCleanupAsyncTaskId())) throw new StaleTaskException("分片清理任务已被替代");
        int expected = expired ? 6 : 2;
        if(task.getStatus() == null || task.getStatus() != expected) throw new StaleTaskException("分片上传状态已变化");
        try {
            context.checkpoint();
            int removed = minioclientUtil.removeFilePartsByFileUuidStrict(task.getFileUuid());
            context.checkpoint();
            if(multifileMapper.markAsyncCleanupSucceeded(uploadTaskId,asyncTaskId,LocalDateTime.now()) != 1) {
                throw new StaleTaskException("分片清理结果被版本栅栏拒绝");
            }
            return Map.of("removedParts",removed);
        } catch(TaskCanceledException | StaleWorkerException | StaleTaskException control) {
            throw control;
        } catch(Exception exception) {
            multifileMapper.markAsyncCleanupFailed(uploadTaskId,asyncTaskId,LocalDateTime.now());
            log.warn("Async multipart cleanup deferred: uploadTaskId={}",uploadTaskId,exception);
            throw new RetryableTaskException("分片对象存储暂时不可用");
        }
    }

    public void cleanupMergedTask(Long taskId, String fileUuid) {
        UploadTask current = multifileMapper.getCleanupById(taskId);
        if(current != null && hasActiveAsyncTask(current.getCleanupAsyncTaskId())) return;
        try {
            int removed = minioclientUtil.removeFilePartsByFileUuidStrict(fileUuid);
            multifileMapper.markPartsCleaned(taskId,LocalDateTime.now());
            log.info("已清理合并后的临时分片: taskId={}, parts={}",taskId,removed);
        } catch (Exception ex) {
            multifileMapper.markPartsCleanupFailed(taskId);
            log.warn("合并后临时分片清理失败，将重试: taskId={}",taskId,ex);
        }
    }

    private void enqueueMergedUploads() {
        for(UploadTask task : multifileMapper.listMergedPendingCleanup(100)) enqueue(task,"MULTIPART_MERGED_CLEANUP");
    }

    private void enqueue(UploadTask upload,String taskType) {
        UnifiedAsyncTask task = taskCenter.createTask(new TaskCreateCommand(
                "multipart-cleanup:" + taskType + ":" + upload.getId(),"cleanup",taskType,
                new DomainTaskPayload(upload.getId()),upload.getUserId(),null,
                "multipart-upload:" + upload.getId(),1));
        multifileMapper.bindCleanupTask(upload.getId(),task.getId(),LocalDateTime.now());
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
}
