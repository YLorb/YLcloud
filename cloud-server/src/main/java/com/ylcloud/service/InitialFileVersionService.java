package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Creates the immutable v1 snapshot when a physical file first becomes version-managed.
 */
@Service
public class InitialFileVersionService {
    private final FileVersionMapper fileVersionMapper;
    private final FileInfoMapper fileInfoMapper;
    private final MinioclientUtil minioclientUtil;

    public InitialFileVersionService(FileVersionMapper fileVersionMapper,
                                     FileInfoMapper fileInfoMapper,
                                     MinioclientUtil minioclientUtil) {
        this.fileVersionMapper = fileVersionMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.minioclientUtil = minioclientUtil;
    }

    /**
     * Idempotently records the currently visible MinIO object as version 1.
     */
    public void ensureInitialVersion(String fileUuid, String displayName, Long userId) {
        if(fileVersionMapper.getMaxVersionNo(fileUuid) > 0) {
            return;
        }
        File file = fileInfoMapper.getFileInfo(fileUuid,userId);
        if(file == null) {
            throw new NotFoundException("文件元数据不存在");
        }

        String minioVersionId;
        try {
            if(!minioclientUtil.isDefaultBucketVersioningEnabled()) {
                throw new ConflictException("MinIO bucket 未开启对象版本控制，无法创建初始版本");
            }
            minioVersionId = minioclientUtil.getCurrentObjectVersionId(fileUuid);
        } catch (ConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new ConflictException("读取文件当前 MinIO 版本失败");
        }
        if(minioVersionId == null || minioVersionId.isBlank()) {
            throw new ConflictException("当前对象缺少 MinIO versionId，无法创建初始版本");
        }

        FileVersion version = new FileVersion();
        version.setFileUuid(fileUuid);
        version.setMinioVersionId(minioVersionId);
        version.setFileName(displayName == null || displayName.isBlank() ? file.getName() : displayName);
        version.setFileHash(file.getHash());
        version.setFileMd5(file.getMd5());
        version.setFileType(file.getType());
        version.setFileSize(file.getSize());
        version.setChangeNote("初始版本");
        version.setCreatedBy(userId);
        version.setCreatetime(LocalDateTime.now());
        fileVersionMapper.insertInitial(version);
    }
}
