package com.ylcloud.service;

import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class SpaceDissolutionDataCleanupService {
    private final SpaceFileMapper spaceFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final PhysicalFileCleanupService physicalCleanupService;
    private final QdrantVectorStoreService vectorStoreService;
    private final SpaceRagChunkRefMapper chunkRefMapper;
    private final SpaceRagDocumentMapper documentMapper;
    private final SpaceMemberMapper memberMapper;
    private final SpaceMapper spaceMapper;

    public SpaceDissolutionDataCleanupService(SpaceFileMapper spaceFileMapper,
                                              FileInfoMapper fileInfoMapper,
                                              PhysicalFileCleanupService physicalCleanupService,
                                              QdrantVectorStoreService vectorStoreService,
                                              SpaceRagChunkRefMapper chunkRefMapper,
                                              SpaceRagDocumentMapper documentMapper,
                                              SpaceMemberMapper memberMapper,
                                              SpaceMapper spaceMapper) {
        this.spaceFileMapper = spaceFileMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.physicalCleanupService = physicalCleanupService;
        this.vectorStoreService = vectorStoreService;
        this.chunkRefMapper = chunkRefMapper;
        this.documentMapper = documentMapper;
        this.memberMapper = memberMapper;
        this.spaceMapper = spaceMapper;
    }

    public void deleteVectors(Long spaceId) {
        vectorStoreService.deleteBySpaceStrict(spaceId);
    }

    /**
     * Idempotently releases every logical Space reference and moves the TEAM to its terminal state.
     * External vector deletion is deliberately completed before this transaction.
     */
    @Transactional
    public void releaseReferencesAndFinalize(Long spaceId) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> fileUuids = new LinkedHashSet<>(spaceFileMapper.listActiveFileUuidsForCleanup(spaceId));
        for (String fileUuid : fileUuids) {
            int released = spaceFileMapper.disableAllByFileUuid(spaceId,fileUuid,now);
            if (released <= 0) continue;
            if (fileInfoMapper.updateFileCount(fileUuid,-released) != 1) {
                throw new IllegalStateException("文件引用计数更新失败: " + fileUuid);
            }
            if (fileInfoMapper.getFileCount(fileUuid) == 0) {
                physicalCleanupService.enqueue(fileUuid);
            }
        }
        chunkRefMapper.disableBySpaceId(spaceId,now);
        documentMapper.purgeBySpaceId(spaceId,now);
        spaceFileMapper.purgeBySpaceId(spaceId,now);
        memberMapper.disableAllBySpaceId(spaceId,now);
        if (spaceMapper.markDissolved(spaceId,now) != 1) {
            throw new IllegalStateException("Space 解散终态写入失败: " + spaceId);
        }
    }
}
