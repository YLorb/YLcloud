package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.UploadChunk;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.utils.UuidUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 单个分片的持久化租约，避免同一 chunk 的并发写相互覆盖。 */
@Service
public class ChunkUploadLeaseService {
    private final ChunkUploadMapper chunkUploadMapper;
    private final MultifileMapper multifileMapper;
    private final long leaseSeconds;

    public ChunkUploadLeaseService(ChunkUploadMapper chunkUploadMapper,
                                   MultifileMapper multifileMapper,
                                   @Value("${ylcloud.upload.chunk-lease-seconds:3600}") long leaseSeconds) {
        this.chunkUploadMapper = chunkUploadMapper;
        this.multifileMapper = multifileMapper;
        this.leaseSeconds = Math.max(60,leaseSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease reserve(String uploadId, Integer chunkIndex, String chunkMd5, long size, String objectName) {
        LocalDateTime now = LocalDateTime.now();
        UploadChunk proposed = new UploadChunk();
        proposed.setUploadId(uploadId);
        proposed.setChunkIndex(chunkIndex);
        proposed.setChunkMd5(chunkMd5);
        proposed.setSize(size);
        proposed.setObjectName(objectName);
        proposed.setStatus(0);
        proposed.setCreatetime(now);
        proposed.setUpdatetime(now);
        chunkUploadMapper.insertIgnore(proposed);

        UploadChunk current = chunkUploadMapper.getAnyByUploadIdAndIndex(uploadId,chunkIndex);
        if(current == null || !Objects.equals(current.getChunkMd5(),chunkMd5)
                || !Objects.equals(current.getSize(),size)
                || !Objects.equals(current.getObjectName(),objectName)) {
            throw new ConflictException("同一分片序号的内容与已有上传请求不一致");
        }
        if(Integer.valueOf(1).equals(current.getStatus())) {
            return new Lease(true,null,objectName);
        }

        String token = UuidUtil.randomUuid();
        if(chunkUploadMapper.claim(uploadId,chunkIndex,token,now.plusSeconds(leaseSeconds),now) == 0) {
            throw new ConflictException("该分片正在上传，请稍后重试");
        }
        return new Lease(false,token,objectName);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String uploadId, Integer chunkIndex, String token) {
        if(chunkUploadMapper.markComplete(uploadId,chunkIndex,token,LocalDateTime.now()) == 0) {
            UploadChunk current = chunkUploadMapper.getAnyByUploadIdAndIndex(uploadId,chunkIndex);
            if(current != null && Integer.valueOf(1).equals(current.getStatus())) {
                return;
            }
            throw new ConflictException("分片上传租约已失效，请重试");
        }
        if(multifileMapper.increaseUploadedChunks(uploadId) == 0) {
            throw new BaseException("上传任务已不可写");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String uploadId, Integer chunkIndex, String token) {
        chunkUploadMapper.release(uploadId,chunkIndex,token,LocalDateTime.now());
    }

    public record Lease(boolean completed, String token, String objectName) {}
}
