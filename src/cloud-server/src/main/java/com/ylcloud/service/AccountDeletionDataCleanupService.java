package com.ylcloud.service;

import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.entity.File;
import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.PhysicalFileCleanupTaskMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账号注销的数据面清理。团队空间中的引用不属于个人数据，绝不能在这里删除。
 */
@Service
public class AccountDeletionDataCleanupService {
    private final FileInfoMapper fileInfoMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final PhysicalFileCleanupService physicalCleanupService;
    private final PhysicalFileCleanupTaskMapper cleanupTaskMapper;
    private final QdrantVectorStoreService vectorStoreService;
    private final SpaceMemberMapper spaceMemberMapper;
    private final SpaceRagChunkRefMapper chunkRefMapper;
    private final SpaceRagDocumentMapper documentMapper;

    public AccountDeletionDataCleanupService(FileInfoMapper fileInfoMapper,
                                             SpaceMapper spaceMapper,
                                             SpaceFileMapper spaceFileMapper,
                                             PhysicalFileCleanupService physicalCleanupService,
                                             PhysicalFileCleanupTaskMapper cleanupTaskMapper,
                                             QdrantVectorStoreService vectorStoreService,
                                             SpaceMemberMapper spaceMemberMapper,
                                             SpaceRagChunkRefMapper chunkRefMapper,
                                             SpaceRagDocumentMapper documentMapper) {
        this.fileInfoMapper = fileInfoMapper;
        this.spaceMapper = spaceMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.physicalCleanupService = physicalCleanupService;
        this.cleanupTaskMapper = cleanupTaskMapper;
        this.vectorStoreService = vectorStoreService;
        this.spaceMemberMapper = spaceMemberMapper;
        this.chunkRefMapper = chunkRefMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 释放个人网盘和 PERSONAL Space 引用。只有全局引用计数归零才提交物理删除。
     */
    @Transactional
    public Map<String, Object> releasePersonalReferences(Long userId) {
        Set<String> queued = new LinkedHashSet<>();
        Set<String> retained = new LinkedHashSet<>();
        Set<String> userFileUuids = new LinkedHashSet<>();
        for (File file : fileInfoMapper.listAllByUserId(userId)) {
            if (file.getFileUuid() != null) userFileUuids.add(file.getFileUuid());
        }
        for (String uuid : userFileUuids) {
            releaseUserReferences(userId, uuid, queued, retained);
        }
        fileInfoMapper.purgeRemainingByUserId(userId, LocalDateTime.now());

        Space personalSpace = spaceMapper.getActivePersonalByOwnerId(userId);
        if (personalSpace != null) {
            Set<String> spaceUuids = new LinkedHashSet<>();
            for (SpaceFile file : spaceFileMapper.listAll(personalSpace.getId())) {
                if (file.getFileUuid() != null) spaceUuids.add(file.getFileUuid());
            }
            for (String uuid : spaceUuids) {
                int released = spaceFileMapper.disableAllByFileUuid(personalSpace.getId(), uuid, LocalDateTime.now());
                releasePhysicalReferences(uuid, released, queued, retained);
            }
        }

        return Map.of(
                "queuedFileUuids", new ArrayList<>(queued),
                "retainedSharedFileUuids", new ArrayList<>(retained),
                "personalSpaceId", personalSpace == null ? 0L : personalSpace.getId(),
                "releasedPersonalFiles", userFileUuids.size()
        );
    }

    private void releaseUserReferences(Long userId, String uuid, Set<String> queued, Set<String> retained) {
        int released = fileInfoMapper.softDeleteByFileUuid(uuid, userId);
        releasePhysicalReferences(uuid, released, queued, retained);
    }

    private void releasePhysicalReferences(String uuid, int released, Set<String> queued, Set<String> retained) {
        if (released <= 0) return;
        if (fileInfoMapper.updateFileCount(uuid, -released) != 1) {
            throw new IllegalStateException("文件引用计数更新失败: " + uuid);
        }
        if (fileInfoMapper.getFileCount(uuid) == 0) {
            physicalCleanupService.enqueue(uuid);
            queued.add(uuid);
            retained.remove(uuid);
        } else if (!queued.contains(uuid)) {
            retained.add(uuid);
        }
    }

    public void cleanupPersonalVectors(Long personalSpaceId) {
        if (personalSpaceId == null || personalSpaceId <= 0) return;
        try {
            vectorStoreService.deleteBySpaceStrict(personalSpaceId);
        } catch (Exception exception) {
            throw new RetryableTaskException("个人空间向量清理尚未完成", exception);
        }
    }

    public void verifyPhysicalCleanup(List<String> queuedFileUuids) {
        for (String uuid : queuedFileUuids) {
            PhysicalFileCleanupTask task = cleanupTaskMapper.getByFileUuid(uuid);
            if (task == null || !"SUCCESS".equals(task.getTaskStatus())) {
                String status = task == null ? "MISSING" : task.getTaskStatus();
                throw new RetryableTaskException("物理文件清理尚未完成: " + uuid + " (" + status + ")");
            }
        }
    }

    @Transactional
    public void finalizePersonalSpace(Long userId, Long personalSpaceId) {
        if (personalSpaceId == null || personalSpaceId <= 0) return;
        chunkRefMapper.disableBySpaceId(personalSpaceId, LocalDateTime.now());
        documentMapper.purgeBySpaceId(personalSpaceId, LocalDateTime.now());
        spaceFileMapper.purgeBySpaceId(personalSpaceId, LocalDateTime.now());
        spaceMemberMapper.disableAllBySpaceId(personalSpaceId, LocalDateTime.now());
        if (spaceMapper.purgePersonal(personalSpaceId, userId, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("个人空间终态清理失败: " + personalSpaceId);
        }
    }
}
