package com.ylcloud.service;

import com.ylcloud.entity.UploadTask;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.utils.MinioclientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 清理超过保留时间且没有新请求的断点续传分片。 */
@Service
@Slf4j
public class MultipartUploadCleanupService {
    private final MultifileMapper multifileMapper;
    private final ChunkUploadMapper chunkUploadMapper;
    private final MinioclientUtil minioclientUtil;

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
    public void cleanupStaleUploads() {
        multifileMapper.recoverStaleMerges(LocalDateTime.now().minusSeconds(Math.max(60,mergeLeaseSeconds)));
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

    public void cleanupMergedTask(Long taskId, String fileUuid) {
        try {
            int removed = minioclientUtil.removeFilePartsByFileUuidStrict(fileUuid);
            multifileMapper.markPartsCleaned(taskId,LocalDateTime.now());
            log.info("已清理合并后的临时分片: taskId={}, parts={}",taskId,removed);
        } catch (Exception ex) {
            multifileMapper.markPartsCleanupFailed(taskId);
            log.warn("合并后临时分片清理失败，将重试: taskId={}",taskId,ex);
        }
    }
}
