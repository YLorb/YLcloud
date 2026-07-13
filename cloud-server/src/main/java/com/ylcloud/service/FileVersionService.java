package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.FileVersionVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文件历史版本业务服务。
 */
@Service
public class FileVersionService {
    private static final Logger log = LoggerFactory.getLogger(FileVersionService.class);
    private static final long MAX_TEXT_PREVIEW_SIZE = 1024 * 1024;

    @Value("${ylcloud.upload.max-file-size:2147483648}")
    private Long maxFileSize;

    private final FileVersionMapper fileVersionMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final SpacePermissionService spacePermissionService;
    private final SpaceFileService spaceFileService;
    private final SpaceRagService spaceRagService;
    private final MinioclientUtil minioclientUtil;
    private final SiteSettingService siteSettingService;
    private final CrossStoreFileWriteService crossStoreFileWriteService;
    private final CrossStoreOperationService crossStoreOperationService;

    /**
     * 初始化 FileVersionService 对象。
     *
     * @param fileVersionMapper 方法入参
     * @param fileInfoMapper 方法入参
     * @param spaceFileMapper 方法入参
     * @param spacePermissionService 方法入参
     * @param spaceFileService 空间文件服务
     * @param spaceRagService 空间 RAG 服务
     * @param minioclientUtil 方法入参
     */
    public FileVersionService(FileVersionMapper fileVersionMapper,
                              FileInfoMapper fileInfoMapper,
                              SpaceFileMapper spaceFileMapper,
                              SpacePermissionService spacePermissionService,
                              SpaceFileService spaceFileService,
                              SpaceRagService spaceRagService,
                              MinioclientUtil minioclientUtil,
                              SiteSettingService siteSettingService,
                              CrossStoreFileWriteService crossStoreFileWriteService,
                              CrossStoreOperationService crossStoreOperationService) {
        this.fileVersionMapper = fileVersionMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.spacePermissionService = spacePermissionService;
        this.spaceFileService = spaceFileService;
        this.spaceRagService = spaceRagService;
        this.minioclientUtil = minioclientUtil;
        this.siteSettingService = siteSettingService;
        this.crossStoreFileWriteService = crossStoreFileWriteService;
        this.crossStoreOperationService = crossStoreOperationService;
    }

    /**
     * 上传 uploadSpaceFileVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param uploadFile 上传文件
     * @param changeNote 变更说明
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public FileVersionVO uploadSpaceFileVersion(Long spaceId, Long spaceFileId, MultipartFile uploadFile, String changeNote, Long userId) {
        return uploadSpaceFileVersion(spaceId,spaceFileId,uploadFile,changeNote,userId,null);
    }

    @Transactional
    public FileVersionVO uploadSpaceFileVersion(Long spaceId,
                                                Long spaceFileId,
                                                MultipartFile uploadFile,
                                                String changeNote,
                                                Long userId,
                                                String idempotencyKey) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        if(!spaceFileService.resolveEffectiveVersionEnabled(spaceFile)) {
            throw new BaseException("该文件未开启历史版本维护");
        }
        if(uploadFile == null || uploadFile.isEmpty()) {
            throw new BaseException("新版本文件不能为空");
        }

        String fileName = resolveVersionFileName(uploadFile,spaceFile);
        validateVersionUploadFile(uploadFile,fileName);

        String md5;
        String sha1;
        String hash;
        try {
            md5 = Md5Util.md5(uploadFile.getInputStream());
            sha1 = HashUtil.sha1(uploadFile.getInputStream());
            hash = HashUtil.sha256(uploadFile.getInputStream());
        } catch (Exception e) {
            throw new BaseException("新版本文件哈希计算失败");
        }

        lockPhysicalFile(spaceFile.getFileUuid());
        AtomicReference<String> resultRef = new AtomicReference<>();
        CrossStoreOperation operation = claimOperation(idempotencyKey,"VERSION_UPLOAD",userId,
                CrossStoreOperationService.payloadHash(spaceId,spaceFileId,fileName,uploadFile.getSize(),hash),
                spaceFile.getFileUuid(),resultRef);
        if(operation != null && CrossStoreOperationService.SUCCESS.equals(operation.getOperationStatus())) {
            return replayVersion(operation,spaceFile);
        }
        FileVersion current = fileVersionMapper.getCurrent(spaceFile.getFileUuid());
        if(sameContent(current,hash,md5,uploadFile.getSize())) {
            resultRef.set(String.valueOf(current.getId()));
            crossStoreOperationService.recordResultCandidate(operation.getOperationKey(),resultRef.get());
            return toVO(spaceId,spaceFileId,current);
        }
        fileName = resolveAvailableSpaceName(spaceFile,fileName);

        ensureMinioVersioningEnabled();
        String versionId;
        try {
            versionId = crossStoreFileWriteService.putNewVersion(uploadFile,spaceFile.getFileUuid());
        } catch (Exception e) {
            throw new RuntimeException("新版本文件上传失败",e);
        }
        if(versionId == null || versionId.isBlank()) {
            throw new BaseException("MinIO bucket 未开启对象版本控制，无法维护历史版本");
        }
        crossStoreOperationService.recordExternalRef(operation.getOperationKey(),versionId);

        String fileType = getFileType(fileName);
        updateCurrentFile(spaceId,spaceFileId,spaceFile.getFileUuid(),fileName,fileType,uploadFile.getSize(),md5,sha1,hash);
        FileVersion version = createVersion(spaceFile.getFileUuid(),versionId,fileName,hash,md5,fileType,uploadFile.getSize(),changeNote,userId);
        resultRef.set(String.valueOf(version.getId()));
        crossStoreOperationService.recordResultCandidate(operation.getOperationKey(),resultRef.get());
        scheduleRagRebuildAfterCommit(spaceId,spaceFileId,userId);
        return toVO(spaceId,spaceFileId,version);
    }

    /**
     * 查询 listSpaceFileVersions 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<FileVersionVO> listSpaceFileVersions(Long spaceId, Long spaceFileId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        List<FileVersionVO> result = new ArrayList<>();
        for(FileVersion version : fileVersionMapper.listByFileUuid(spaceFile.getFileUuid())) {
            result.add(toVO(spaceId,spaceFileId,version));
        }
        return result;
    }

    /**
     * 预览 previewVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param versionRecordId 版本记录 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public FilePreviewVO previewVersion(Long spaceId, Long spaceFileId, Long versionRecordId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        FileVersion version = requireVersion(spaceFile,versionRecordId);
        String contentType = resolveContentType(version.getFileName(),version.getFileType());
        String previewType = resolvePreviewType(contentType,version.getFileName());
        FilePreviewVO vo = new FilePreviewVO();
        vo.setFileUuid(spaceFile.getFileUuid());
        vo.setName(version.getFileName());
        vo.setPreviewType(previewType);
        vo.setContentType(contentType);
        vo.setSize(version.getFileSize());
        if("text".equals(previewType)) {
            vo.setTextContent(readVersionText(version));
            return vo;
        }
        if(isStreamPreviewType(previewType)) {
            vo.setPreviewUrl("/api/space/" + spaceId + "/files/" + spaceFileId + "/versions/" + versionRecordId + "/preview/stream");
            return vo;
        }
        throw new BaseException("当前文件类型不支持预览");
    }

    /**
     * 预览 previewVersionStream 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param versionRecordId 版本记录 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void previewVersionStream(Long spaceId, Long spaceFileId, Long versionRecordId, Long userId, HttpServletResponse response) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        FileVersion version = requireVersion(spaceFile,versionRecordId);
        String contentType = resolveContentType(version.getFileName(),version.getFileType());
        String previewType = resolvePreviewType(contentType,version.getFileName());
        if("text".equals(previewType)) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        if(!isStreamPreviewType(previewType) && !"text".equals(previewType)) {
            throw new BaseException("当前文件类型不支持预览");
        }
        try {
            minioclientUtil.previewObject(spaceFile.getFileUuid(),version.getMinioVersionId(),version.getFileName(),contentType,response);
        } catch (Exception e) {
            throw new RuntimeException("历史版本预览失败",e);
        }
    }

    /**
     * 下载 downloadVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param versionRecordId 版本记录 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void downloadVersion(Long spaceId, Long spaceFileId, Long versionRecordId, Long userId, HttpServletResponse response) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        FileVersion version = requireVersion(spaceFile,versionRecordId);
        try {
            minioclientUtil.getObject(spaceFile.getFileUuid(),version.getMinioVersionId(),version.getFileName(),response);
        } catch (Exception e) {
            throw new RuntimeException("历史版本下载失败",e);
        }
    }

    /**
     * 恢复 restoreVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param versionRecordId 版本记录 ID
     * @param changeNote 变更说明
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public FileVersionVO restoreVersion(Long spaceId, Long spaceFileId, Long versionRecordId, String changeNote, Long userId) {
        return restoreVersion(spaceId,spaceFileId,versionRecordId,changeNote,userId,null);
    }

    @Transactional
    public FileVersionVO restoreVersion(Long spaceId,
                                        Long spaceFileId,
                                        Long versionRecordId,
                                        String changeNote,
                                        Long userId,
                                        String idempotencyKey) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceFile spaceFile = requireVersionableSpaceFile(spaceId,spaceFileId);
        if(!spaceFileService.resolveEffectiveVersionEnabled(spaceFile)) {
            throw new BaseException("该文件未开启历史版本维护");
        }
        FileVersion source = requireVersion(spaceFile,versionRecordId);
        lockPhysicalFile(spaceFile.getFileUuid());
        FileVersion current = fileVersionMapper.getCurrent(spaceFile.getFileUuid());
        boolean createCopy = isExactRestoreTarget(spaceFile,current,source);
        AtomicReference<String> resultRef = new AtomicReference<>();
        CrossStoreOperation operation = claimOperation(idempotencyKey,"VERSION_RESTORE",userId,
                CrossStoreOperationService.payloadHash(spaceId,spaceFileId,versionRecordId,source.getMinioVersionId()),
                createCopy ? com.ylcloud.utils.UuidUtil.randomUuid() : spaceFile.getFileUuid(),resultRef);
        if(operation != null && CrossStoreOperationService.SUCCESS.equals(operation.getOperationStatus())) {
            return replayVersionInSpace(operation,spaceId);
        }
        if(createCopy) {
            return restoreVersionAsCopy(spaceFile,source,changeNote,userId,operation,resultRef);
        }
        String restoredName = resolveAvailableSpaceName(spaceFile,source.getFileName());
        ensureMinioVersioningEnabled();
        String newMinioVersionId;
        try {
            newMinioVersionId = crossStoreFileWriteService.restoreAsNewVersion(spaceFile.getFileUuid(),source.getMinioVersionId());
        } catch (Exception e) {
            throw new RuntimeException("历史版本恢复失败",e);
        }
        if(newMinioVersionId == null || newMinioVersionId.isBlank()) {
            throw new BaseException("MinIO bucket 未开启对象版本控制，无法维护历史版本");
        }
        crossStoreOperationService.recordExternalRef(operation.getOperationKey(),newMinioVersionId);

        updateCurrentFile(spaceId,spaceFileId,spaceFile.getFileUuid(),restoredName,source.getFileType(),source.getFileSize(),source.getFileMd5(),null,source.getFileHash());
        String note = changeNote == null || changeNote.isBlank() ? "恢复自版本 " + source.getVersionNo() : changeNote;
        FileVersion version = createVersion(spaceFile.getFileUuid(),newMinioVersionId,restoredName,source.getFileHash(),source.getFileMd5(),source.getFileType(),source.getFileSize(),note,userId);
        resultRef.set(String.valueOf(version.getId()));
        crossStoreOperationService.recordResultCandidate(operation.getOperationKey(),resultRef.get());
        scheduleRagRebuildAfterCommit(spaceId,spaceFileId,userId);
        return toVO(spaceId,spaceFileId,version);
    }

    private FileVersionVO restoreVersionAsCopy(SpaceFile sourceNode,
                                               FileVersion sourceVersion,
                                               String changeNote,
                                               Long userId,
                                               CrossStoreOperation operation,
                                               AtomicReference<String> resultRef) {
        String copyUuid = operation.getResourceId();
        String copyName = resolveCopySpaceName(sourceNode,sourceVersion.getFileName());
        ensureMinioVersioningEnabled();
        String copyMinioVersionId;
        try {
            copyMinioVersionId = crossStoreFileWriteService.copyVersionToNewObject(
                    sourceNode.getFileUuid(),sourceVersion.getMinioVersionId(),copyUuid);
        } catch (Exception e) {
            throw new RuntimeException("历史版本副本恢复失败",e);
        }
        if(copyMinioVersionId == null || copyMinioVersionId.isBlank()) {
            throw new BaseException("MinIO bucket 未开启对象版本控制，无法创建历史版本副本");
        }
        crossStoreOperationService.recordExternalRef(operation.getOperationKey(),copyMinioVersionId);

        LocalDateTime now = LocalDateTime.now();
        File copyFile = File.builder()
                .fileUuid(copyUuid)
                .dir(false)
                .name(copyName)
                .type(sourceVersion.getFileType())
                .size(sourceVersion.getFileSize())
                .md5(sourceVersion.getFileMd5())
                .hash(sourceVersion.getFileHash())
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        if(fileInfoMapper.insertFileInfo(copyFile) == 0) {
            throw new BaseException("历史版本副本元数据保存失败");
        }

        SpaceFile copyNode = new SpaceFile();
        copyNode.setSpaceId(sourceNode.getSpaceId());
        copyNode.setFileUuid(copyUuid);
        copyNode.setFileName(copyName);
        copyNode.setDir(0);
        copyNode.setParentId(sourceNode.getParentId());
        copyNode.setPath(replacePathName(sourceNode.getPath(),copyName));
        copyNode.setVersionEnabled(sourceNode.getVersionEnabled());
        copyNode.setStatus(StatusConstant.ENABLE);
        copyNode.setCreatedBy(userId);
        copyNode.setCreatetime(now);
        copyNode.setUpdatetime(now);
        if(spaceFileMapper.insert(copyNode) == 0) {
            throw new BaseException("历史版本副本节点保存失败");
        }

        String note = changeNote == null || changeNote.isBlank()
                ? "复制自版本 " + sourceVersion.getVersionNo() : changeNote;
        FileVersion copyVersion = createVersion(copyUuid,copyMinioVersionId,copyName,
                sourceVersion.getFileHash(),sourceVersion.getFileMd5(),sourceVersion.getFileType(),
                sourceVersion.getFileSize(),note,userId);
        resultRef.set(String.valueOf(copyVersion.getId()));
        crossStoreOperationService.recordResultCandidate(operation.getOperationKey(),resultRef.get());
        scheduleRagRebuildAfterCommit(sourceNode.getSpaceId(),copyNode.getId(),userId);
        return toVO(sourceNode.getSpaceId(),copyNode.getId(),copyVersion);
    }

    /**
     * 调度 scheduleRagRebuildAfterCommit 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param userId 用户 ID
     */
    private void scheduleRagRebuildAfterCommit(Long spaceId, Long spaceFileId, Long userId) {
        Runnable task = () -> {
            try {
                spaceRagService.rebuildFile(spaceId,spaceFileId,userId);
            } catch (Exception ex) {
                log.warn("Failed to schedule RAG rebuild after file version change, spaceId={}, spaceFileId={}",
                        spaceId,spaceFileId,ex);
            }
        };
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /**
     * 校验 requireVersionableSpaceFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @return 处理结果
     */
    private SpaceFile requireVersionableSpaceFile(Long spaceId, Long spaceFileId) {
        SpaceFile spaceFile = spaceFileMapper.getById(spaceId,spaceFileId);
        if(spaceFile == null || spaceFile.getDir() == 1 || spaceFile.getFileUuid() == null) {
            throw new NotFoundException("空间文件不存在或不是普通文件");
        }
        return spaceFile;
    }

    /**
     * 校验 requireVersion 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param versionRecordId 版本记录 ID
     * @return 处理结果
     */
    private FileVersion requireVersion(SpaceFile spaceFile, Long versionRecordId) {
        FileVersion version = fileVersionMapper.getByIdAndFileUuid(versionRecordId,spaceFile.getFileUuid());
        if(version == null) {
            throw new NotFoundException("历史版本不存在");
        }
        return version;
    }

    /**
     * 创建 createVersion 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param minioVersionId 方法入参
     * @param fileName 文件名
     * @param hash 文件哈希
     * @param md5 文件 MD5
     * @param type 类型
     * @param size 方法入参
     * @param changeNote 变更说明
     * @param userId 用户 ID
     * @return 处理结果
     */
    private FileVersion createVersion(String fileUuid, String minioVersionId, String fileName, String hash, String md5, String type, Long size, String changeNote, Long userId) {
        fileVersionMapper.clearCurrent(fileUuid);
        FileVersion version = new FileVersion();
        version.setFileUuid(fileUuid);
        version.setVersionNo(fileVersionMapper.getMaxVersionNo(fileUuid) + 1);
        version.setMinioVersionId(minioVersionId);
        version.setFileName(fileName);
        version.setFileHash(hash);
        version.setFileMd5(md5);
        version.setFileType(type);
        version.setFileSize(size);
        version.setChangeNote(changeNote);
        version.setCreatedBy(userId);
        version.setCurrent(StatusConstant.ENABLE);
        version.setStatus(StatusConstant.ENABLE);
        version.setCreatetime(LocalDateTime.now());
        fileVersionMapper.insert(version);
        return version;
    }

    /**
     * 更新 updateCurrentFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param fileUuid 文件 UUID
     * @param fileName 文件名
     * @param type 类型
     * @param size 方法入参
     * @param md5 文件 MD5
     * @param hash 文件哈希
     */
    private void updateCurrentFile(Long spaceId, Long spaceFileId, String fileUuid, String fileName, String type, Long size, String md5, String sha1, String hash) {
        int rows = fileInfoMapper.updatePhysicalFileInfo(fileUuid,fileName,type,size,md5,sha1,hash,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件当前版本元数据更新失败");
        }
        if(spaceFileMapper.updateFileName(spaceId,spaceFileId,fileName,LocalDateTime.now()) == 0) {
            throw new BaseException("空间文件版本已发生变化，请重试");
        }
    }

    private void lockPhysicalFile(String fileUuid) {
        if(fileInfoMapper.getPhysicalFileForUpdate(fileUuid) == null) {
            throw new NotFoundException("物理文件不存在");
        }
    }

    private boolean sameContent(FileVersion version, String hash, String md5, Long size) {
        return version != null
                && Objects.equals(version.getFileHash(),hash)
                && Objects.equals(version.getFileMd5(),md5)
                && Objects.equals(version.getFileSize(),size);
    }

    private boolean isExactRestoreTarget(SpaceFile node, FileVersion current, FileVersion source) {
        return Objects.equals(node.getFileName(),source.getFileName())
                && sameContent(current,source.getFileHash(),source.getFileMd5(),source.getFileSize());
    }

    private String resolveAvailableSpaceName(SpaceFile target, String desiredName) {
        if(spaceFileMapper.countSameNameExcluding(target.getSpaceId(),target.getParentId(),desiredName,0,target.getId()) == 0) {
            return desiredName;
        }
        NameParts parts = splitName(desiredName);
        for(int index = 1; index < 10000; index++) {
            String candidate = parts.base() + "-副本(" + index + ")" + parts.extension();
            if(spaceFileMapper.countSameNameExcluding(target.getSpaceId(),target.getParentId(),candidate,0,target.getId()) == 0) {
                return candidate;
            }
        }
        throw new BaseException("无法生成可用的恢复副本名称");
    }

    private String resolveCopySpaceName(SpaceFile target, String desiredName) {
        NameParts parts = splitName(desiredName);
        for(int index = 1; index < 10000; index++) {
            String candidate = parts.base() + "-副本(" + index + ")" + parts.extension();
            if(spaceFileMapper.countSameName(target.getSpaceId(),target.getParentId(),candidate,0) == 0) {
                return candidate;
            }
        }
        throw new BaseException("无法生成可用的恢复副本名称");
    }

    private String replacePathName(String path, String fileName) {
        if(path == null || path.isBlank()) {
            return "/" + fileName;
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? fileName : path.substring(0,slash + 1) + fileName;
    }

    private NameParts splitName(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if(dot <= 0) {
            return new NameParts(fileName == null ? "文件" : fileName,"");
        }
        return new NameParts(fileName.substring(0,dot),fileName.substring(dot));
    }

    private record NameParts(String base, String extension) {}

    private CrossStoreOperation claimOperation(String idempotencyKey,
                                               String operationType,
                                               Long userId,
                                               String payloadHash,
                                               String resourceId,
                                               AtomicReference<String> resultRef) {
        String effectiveKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? com.ylcloud.utils.UuidUtil.randomUuid() : idempotencyKey;
        if(effectiveKey.length() > 128 || !effectiveKey.matches("^[A-Za-z0-9._:-]{8,128}$")) {
            throw new BaseException("Idempotency-Key 格式不正确");
        }
        String operationKey = CrossStoreOperationService.key(operationType,userId,effectiveKey);
        CrossStoreOperation operation = crossStoreOperationService.claim(
                operationKey,operationType,payloadHash,resourceId);
        if(!CrossStoreOperationService.SUCCESS.equals(operation.getOperationStatus())) {
            crossStoreOperationService.completeAfterCommit(operationKey,resultRef::get);
        }
        return operation;
    }

    private FileVersionVO replayVersion(CrossStoreOperation operation, SpaceFile spaceFile) {
        if(operation.getResultRef() == null || !operation.getResultRef().matches("\\d+")) {
            throw new BaseException("版本操作已完成，但结果引用不可用");
        }
        FileVersion version = fileVersionMapper.getByIdAndFileUuid(
                Long.valueOf(operation.getResultRef()),spaceFile.getFileUuid());
        if(version == null) {
            throw new BaseException("幂等版本结果已不可用，请使用新的 Idempotency-Key");
        }
        return toVO(spaceFile.getSpaceId(),spaceFile.getId(),version);
    }

    private FileVersionVO replayVersionInSpace(CrossStoreOperation operation, Long spaceId) {
        if(operation.getResultRef() == null || !operation.getResultRef().matches("\\d+")) {
            throw new BaseException("版本恢复已完成，但结果引用不可用");
        }
        FileVersion version = fileVersionMapper.getById(Long.valueOf(operation.getResultRef()));
        if(version == null) {
            throw new BaseException("幂等版本恢复结果已不可用，请使用新的 Idempotency-Key");
        }
        SpaceFile node = spaceFileMapper.getActiveByFileUuid(spaceId,version.getFileUuid());
        if(node == null) {
            throw new BaseException("幂等版本恢复结果不属于当前 Space");
        }
        return toVO(spaceId,node.getId(),version);
    }

    /**
     * 确保 ensureMinioVersioningEnabled 相关逻辑。
     */
    private void ensureMinioVersioningEnabled() {
        try {
            if(!minioclientUtil.isDefaultBucketVersioningEnabled()) {
                throw new BaseException("MinIO bucket 未开启对象版本控制，无法维护历史版本");
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("检查 MinIO bucket 版本控制状态失败",e);
        }
    }

    /**
     * 转换 toVO 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param version 方法入参
     * @return 处理结果
     */
    private FileVersionVO toVO(Long spaceId, Long spaceFileId, FileVersion version) {
        FileVersionVO vo = new FileVersionVO();
        vo.setId(version.getId());
        vo.setFileUuid(version.getFileUuid());
        vo.setVersionNo(version.getVersionNo());
        vo.setMinioVersionId(version.getMinioVersionId());
        vo.setFileName(version.getFileName());
        vo.setFileHash(version.getFileHash());
        vo.setFileMd5(version.getFileMd5());
        vo.setFileType(version.getFileType());
        vo.setFileSize(version.getFileSize());
        vo.setChangeNote(version.getChangeNote());
        vo.setCreatedBy(version.getCreatedBy());
        vo.setCurrent(version.getCurrent());
        vo.setCreatetime(version.getCreatetime());
        String base = "/api/space/" + spaceId + "/files/" + spaceFileId + "/versions/" + version.getId();
        vo.setPreviewUrl(base + "/preview");
        vo.setStreamUrl(base + "/preview/stream");
        vo.setDownloadUrl(base + "/download");
        return vo;
    }

    /**
     * 执行 readVersionText 函数的业务处理。
     *
     * @param version 方法入参
     * @return 处理结果
     */
    private String readVersionText(FileVersion version) {
        if(version.getFileSize() != null && version.getFileSize() > MAX_TEXT_PREVIEW_SIZE) {
            throw new BaseException("文本文件过大，不支持直接预览");
        }
        try (InputStream inputStream = minioclientUtil.getObjectStream(version.getFileUuid(),version.getMinioVersionId())) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("历史版本文本预览失败",e);
        }
    }

    /**
     * 查询 getFileType 相关逻辑。
     *
     * @param fileName 文件名
     * @return 处理结果
     */
    private String resolveVersionFileName(MultipartFile uploadFile, SpaceFile spaceFile) {
        String originalFilename = uploadFile.getOriginalFilename();
        String fileName = originalFilename == null || originalFilename.isBlank() ? spaceFile.getFileName() : originalFilename;
        if(fileName == null) {
            throw new BaseException("文件名不能为空");
        }
        return fileName.trim();
    }

    private void validateVersionUploadFile(MultipartFile uploadFile, String fileName) {
        if(uploadFile.getSize() > siteSettingService.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }
        if(fileName.isEmpty() || fileName.length() > 255) {
            throw new BaseException("文件名不能为空且不能超过 255 个字符");
        }
        if(fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new BaseException("文件名包含非法路径字符");
        }
    }

    private String getFileType(String fileName) {
        if(fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    /**
     * 执行 isStreamPreviewType 函数的业务处理。
     *
     * @param previewType 方法入参
     * @return 处理结果
     */
    private boolean isStreamPreviewType(String previewType) {
        return "image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType) || "audio".equals(previewType);
    }

    /**
     * 解析 resolvePreviewType 相关逻辑。
     *
     * @param contentType 方法入参
     * @param fileName 文件名
     * @return 处理结果
     */
    private String resolvePreviewType(String contentType, String fileName) {
        if(contentType.startsWith("image/")) return "image";
        if("application/pdf".equals(contentType)) return "pdf";
        if(contentType.startsWith("video/")) return "video";
        if(contentType.startsWith("audio/")) return "audio";
        if(contentType.startsWith("text/") || hasExtension(fileName,".md",".json",".xml",".csv",".log",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) {
            return "text";
        }
        return "unsupported";
    }

    /**
     * 解析 resolveContentType 相关逻辑。
     *
     * @param fileName 文件名
     * @param storedType 方法入参
     * @return 处理结果
     */
    private String resolveContentType(String fileName, String storedType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        String lowerType = storedType == null ? "" : storedType.toLowerCase();
        String key = lowerName.isEmpty() ? lowerType : lowerName;
        if(hasExtension(key,".jpg",".jpeg")) return "image/jpeg";
        if(hasExtension(key,".png")) return "image/png";
        if(hasExtension(key,".gif")) return "image/gif";
        if(hasExtension(key,".webp")) return "image/webp";
        if(hasExtension(key,".bmp")) return "image/bmp";
        if(hasExtension(key,".svg")) return "image/svg+xml";
        if(hasExtension(key,".pdf")) return "application/pdf";
        if(hasExtension(key,".mp4")) return "video/mp4";
        if(hasExtension(key,".webm")) return "video/webm";
        if(hasExtension(key,".ogg",".ogv")) return "video/ogg";
        if(hasExtension(key,".mp3")) return "audio/mpeg";
        if(hasExtension(key,".wav")) return "audio/wav";
        if(hasExtension(key,".m4a")) return "audio/mp4";
        if(hasExtension(key,".flac")) return "audio/flac";
        if(hasExtension(key,".txt",".md",".log",".csv",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) return "text/plain;charset=UTF-8";
        if(hasExtension(key,".json")) return "application/json;charset=UTF-8";
        if(hasExtension(key,".xml")) return "application/xml;charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * 判断 hasExtension 相关逻辑。
     *
     * @param fileName 文件名
     * @param extensions 方法入参
     * @return 处理结果
     */
    private boolean hasExtension(String fileName, String... extensions) {
        if(fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for(String extension : extensions) {
            if(lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
