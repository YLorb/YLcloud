package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceRagChunkRef;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 索引的 MySQL 本地事务边界。
 *
 * Qdrant 写入发生在事务外；该服务保证数据库侧始终满足：只有文档 SUCCESS 时，
 * 对应引用才会处于启用状态。
 */
@Service
public class RagIndexTransactionService {
    private final SpaceRagDocumentMapper documentMapper;
    private final SpaceRagChunkRefMapper chunkRefMapper;

    public RagIndexTransactionService(SpaceRagDocumentMapper documentMapper,
                                      SpaceRagChunkRefMapper chunkRefMapper) {
        this.documentMapper = documentMapper;
        this.chunkRefMapper = chunkRefMapper;
    }

    @Transactional
    public void beginIndex(Long documentId) {
        LocalDateTime now = LocalDateTime.now();
        if(documentMapper.beginIndex(documentId,now) != 1) {
            throw new ConflictException("RAG 文档正在索引、清理或已经被删除");
        }
        chunkRefMapper.disableByDocumentId(documentId,now);
    }

    @Transactional
    public void commitIndex(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        if(chunks == null || chunks.isEmpty()) {
            throw new BaseException("RAG 索引提交要求至少一个有效切片");
        }
        LocalDateTime now = LocalDateTime.now();
        for(FileRagChunk chunk : chunks) {
            SpaceRagChunkRef ref = new SpaceRagChunkRef();
            ref.setSpaceId(spaceId);
            ref.setDocumentId(documentId);
            ref.setSpaceFileId(spaceFileId);
            ref.setFileChunkId(chunk.getId());
            ref.setStatus(StatusConstant.ENABLE);
            ref.setCreatetime(now);
            ref.setUpdatetime(now);
            chunkRefMapper.insert(ref);
        }
        int activeRefs = chunkRefMapper.countActiveByDocumentId(documentId);
        if(activeRefs != chunks.size()) {
            throw new BaseException("RAG 数据库引用数量与有效切片数量不一致");
        }
        if(documentMapper.commitIndex(documentId,activeRefs,LocalDateTime.now()) != 1) {
            throw new ConflictException("RAG 文档状态已变化，拒绝提交过期索引");
        }
    }

    @Transactional
    public boolean failBuildingIndex(Long documentId, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        if(documentMapper.failIfBuilding(documentId,errorMessage,now) != 1) {
            return false;
        }
        chunkRefMapper.disableByDocumentId(documentId,now);
        return true;
    }

    @Transactional
    public boolean failActiveIndex(Long documentId, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        if(documentMapper.failIfActive(documentId,errorMessage,now) != 1) {
            return false;
        }
        chunkRefMapper.disableByDocumentId(documentId,now);
        return true;
    }

    @Transactional
    public boolean claimCleanup(Long documentId) {
        LocalDateTime now = LocalDateTime.now();
        if(documentMapper.claimCleanup(documentId,now) != 1) {
            return false;
        }
        chunkRefMapper.disableByDocumentId(documentId,now);
        return true;
    }

    @Transactional
    public void completeCleanup(Long documentId) {
        documentMapper.completeCleanup(documentId,LocalDateTime.now());
    }

    @Transactional
    public void releaseCleanup(Long documentId) {
        documentMapper.releaseCleanup(documentId,LocalDateTime.now());
    }
}
