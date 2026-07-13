package com.ylcloud.service;

import com.ylcloud.DTO.MultifileDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.VO.ChunkStatusVO;
import com.ylcloud.VO.FileMergeReqVO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.InitifileVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.constant.UploadTaskConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.UploadChunk;
import com.ylcloud.entity.UploadTask;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.UuidUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 大文件分片上传业务服务。
 *
 * <p>负责上传任务初始化、分片接收、断点进度查询、分片合并、秒传落库等逻辑。</p>
 */
@Service
@Slf4j
public class MultifileService {
    /**
     * 默认单分片大小：5MB。
     */
    private static final long DEFAULT_CHUNK_SIZE = 5L * 1024 * 1024;

    @Value("${ylcloud.upload.max-file-size:2147483648}")
    private Long maxFileSize;

    @Autowired
    private MultifileMapper multifileMapper;

    @Autowired
    private ChunkUploadMapper chunkUploadMapper;

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @Autowired
    private MinioclientUtil minioclientUtil;

    @Autowired
    private FileService fileService;

    @Autowired
    private SiteSettingService siteSettingService;

    /**
     * 初始化 initfile 相关逻辑。
     *
     * @param multifileDTO 分片上传初始化参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public InitifileVO initfile(MultifileDTO multifileDTO, Long userId) {
        validateInitParam(multifileDTO);
        Long parentId = normalizeParentId(multifileDTO.getParentId(), userId);
        String uploadId = normalizeUploadId(multifileDTO.getUploadId());
        String fileKey = uploadFileKey(userId,parentId,multifileDTO.getFileName());

        UploadTask existingTask = multifileMapper.getByUploadId(uploadId,userId);
        if(existingTask != null) {
            return resumeExistingTask(existingTask,multifileDTO,parentId);
        }
        UploadTask occupyingTask = multifileMapper.getActiveByFileKey(fileKey);
        if(occupyingTask != null) {
            throw new ConflictException("同名文件正在上传，请使用原 uploadId 继续断点续传");
        }
        requireNoSameName(multifileDTO.getFileName(), parentId, userId);

        File existingFile = fileInfoMapper.getFileByMd5Sha1Size(
                multifileDTO.getFileMd5(),
                multifileDTO.getFileSha1(),
                multifileDTO.getFileSize());
        if(existingFile != null) {
            log.info("文件已存在，执行秒传: {}", multifileDTO.getFileName());
            FileVO fileVO = reuseExistingFile(existingFile,multifileDTO.getFileName(),parentId,userId);
            InitifileVO initifileVO = new InitifileVO();
            initifileVO.setInstantUpload(true);
            initifileVO.setUploadedChunks(List.of());
            initifileVO.setFile(fileVO);
            return initifileVO;
        }

        Long chunkSize = multifileDTO.getChunkSize() == null ? DEFAULT_CHUNK_SIZE : multifileDTO.getChunkSize();
        Integer totalChunks = multifileDTO.getTotalChunks() == null ?
                Math.toIntExact((multifileDTO.getFileSize() + chunkSize - 1) / chunkSize) :
                multifileDTO.getTotalChunks();

        LocalDateTime now = LocalDateTime.now();
        UploadTask uploadTask = new UploadTask();
        uploadTask.setUploadId(uploadId);
        uploadTask.setFileKey(fileKey);
        uploadTask.setUserId(userId);
        uploadTask.setParentId(parentId);
        uploadTask.setFileName(multifileDTO.getFileName());
        uploadTask.setFileSize(multifileDTO.getFileSize());
        uploadTask.setFileMd5(multifileDTO.getFileMd5());
        uploadTask.setFileSha1(multifileDTO.getFileSha1());
        uploadTask.setFileHash(multifileDTO.getFileHash());
        uploadTask.setChunkSize(chunkSize);
        uploadTask.setTotalChunks(totalChunks);
        uploadTask.setUploadedChunks(0);
        uploadTask.setStatus(UploadTaskConstant.UPLOADING);
        uploadTask.setFileUuid(UuidUtil.randomUuid());
        uploadTask.setLastActivityTime(now);
        uploadTask.setMergeStartedTime(null);
        uploadTask.setCreatetime(now);
        uploadTask.setUpdatetime(now);
        try {
            multifileMapper.insert(uploadTask);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            UploadTask winner = multifileMapper.getActiveByFileKey(fileKey);
            if(winner != null && winner.getUploadId().equals(uploadId)) {
                return resumeExistingTask(winner,multifileDTO,parentId);
            }
            throw new ConflictException("同名文件正在上传，请使用原 uploadId 继续断点续传");
        }
        return buildInitVO(uploadTask,false);
    }

    /**
     * 上传 uploadChunk 相关逻辑。
     *
     * @param file 文件对象
     * @param uploadId 方法入参
     * @param chunkIndex 方法入参
     * @param chunkMd5 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    public Boolean uploadChunk(MultipartFile file, String uploadId, Integer chunkIndex, String chunkMd5, Long userId) {
        if(file == null || file.isEmpty()) {
            throw new BaseException("分片不能为空");
        }
        UploadTask task = requireUploadingTask(uploadId,userId);
        if(chunkIndex == null || chunkIndex < 0 || chunkIndex >= task.getTotalChunks()) {
            throw new BaseException("分片序号不合法");
        }

        UploadChunk exists = chunkUploadMapper.getByUploadIdAndIndex(uploadId,chunkIndex);
        if(exists != null) {
            multifileMapper.touch(uploadId);
            return true;
        }

        validateChunkSize(file,task,chunkIndex);
        validateChunkMd5(file,chunkMd5);

        try {
            String objectName = minioclientUtil.uploadFilePart(task.getFileUuid() == null ? task.getUploadId() : task.getFileUuid(),
                    task.getFileName(),file,chunkIndex,task.getTotalChunks());
            UploadChunk uploadChunk = new UploadChunk();
            uploadChunk.setUploadId(uploadId);
            uploadChunk.setChunkIndex(chunkIndex);
            uploadChunk.setChunkMd5(chunkMd5);
            uploadChunk.setSize(file.getSize());
            uploadChunk.setObjectName(objectName);
            uploadChunk.setStatus(StatusConstant.ENABLE);
            uploadChunk.setCreatetime(LocalDateTime.now());
            uploadChunk.setUpdatetime(LocalDateTime.now());
            int rows = chunkUploadMapper.insertIgnore(uploadChunk);
            if(rows > 0) {
                multifileMapper.increaseUploadedChunks(uploadId);
            }
            return true;
        } catch (Exception e) {
            log.error("分片上传失败: uploadId={}, chunkIndex={}", uploadId, chunkIndex, e);
            throw new BaseException("分片上传失败");
        }
    }

    /**
     * 执行 status 函数的业务处理。
     *
     * @param uploadId 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    public ChunkStatusVO status(String uploadId, Long userId) {
        UploadTask task = multifileMapper.getByUploadId(uploadId,userId);
        if(task == null) {
            throw new BaseException("上传任务不存在");
        }
        List<Integer> uploadedIndexes = chunkUploadMapper.listUploadedIndexes(uploadId);
        return new ChunkStatusVO(uploadId,task.getTotalChunks(),uploadedIndexes.size(),uploadedIndexes);
    }

    /**
     * 合并 merge 相关逻辑。
     *
     * @param uploadId 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public FileVO merge(String uploadId, Long userId) {
        UploadTask task = multifileMapper.getByUploadId(uploadId,userId);
        if(task == null) {
            throw new BaseException("上传任务不存在");
        }
        if(UploadTaskConstant.MERGED.equals(task.getStatus())) {
            return mergedResult(task,userId);
        }
        if(UploadTaskConstant.MERGING.equals(task.getStatus())) {
            throw new ConflictException("文件正在合并，请稍后查询结果");
        }
        if(!UploadTaskConstant.UPLOADING.equals(task.getStatus()) && !UploadTaskConstant.FAIL.equals(task.getStatus())) {
            throw new BaseException("上传任务状态异常");
        }
        List<Integer> uploadedIndexes = chunkUploadMapper.listUploadedIndexes(uploadId);
        if(uploadedIndexes.size() != task.getTotalChunks()) {
            throw new BaseException("分片未上传完整");
        }
        Long parentId = normalizeParentId(task.getParentId(),userId);
        requireNoSameName(task.getFileName(),parentId,userId);

        if(multifileMapper.claimMerge(uploadId,userId) == 0) {
            UploadTask latest = multifileMapper.getByUploadId(uploadId,userId);
            if(latest != null && UploadTaskConstant.MERGED.equals(latest.getStatus())) {
                return mergedResult(latest,userId);
            }
            throw new ConflictException("文件正在合并，请稍后查询结果");
        }
        String fileUuid = task.getFileUuid();
        List<String> objectNames = chunkUploadMapper.listObjectNames(uploadId);
        FileMergeReqVO mergeReqVO = new FileMergeReqVO();
        mergeReqVO.setUploadId(uploadId);
        mergeReqVO.setFileUuid(fileUuid);
        mergeReqVO.setFileName(task.getFileName());
        mergeReqVO.setPartNames(objectNames);

        try {
            if(!minioclientUtil.objectMatchesSize(fileUuid,task.getFileSize())) {
                minioclientUtil.mergeFileParts(mergeReqVO);
            }
            FileVO fileVO = saveMergedFile(task,fileUuid,parentId,userId);
            if(multifileMapper.markMerged(uploadId,fileUuid) == 0) {
                throw new IllegalStateException("上传任务合并状态提交失败");
            }
            removePartsAfterCommit(objectNames);
            return fileVO;
        } catch (Exception e) {
            log.error("分片合并失败: {}", uploadId, e);
            throw new BaseException("分片合并失败");
        }
    }

    /**
     * 校验 validateInitParam 相关逻辑。
     *
     * @param multifileDTO 分片上传初始化参数
     */
    private void validateInitParam(MultifileDTO multifileDTO) {
        if(multifileDTO == null) {
            throw new BaseException("上传参数不能为空");
        }
        if(multifileDTO.getFileName() == null || multifileDTO.getFileName().isBlank()) {
            throw new BaseException("文件名不能为空");
        }
        if(multifileDTO.getFileSize() == null || multifileDTO.getFileSize() <= 0) {
            throw new BaseException("文件大小不合法");
        }
        if(multifileDTO.getFileSize() > siteSettingService.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }
        if(multifileDTO.getFileMd5() == null || multifileDTO.getFileMd5().isBlank()) {
            throw new BaseException("文件 MD5 不能为空");
        }
        if(multifileDTO.getFileSha1() == null || multifileDTO.getFileSha1().isBlank()) {
            throw new BaseException("文件 SHA1 不能为空");
        }
        if(multifileDTO.getFileHash() == null || multifileDTO.getFileHash().isBlank()) {
            throw new BaseException("文件 hash 不能为空");
        }
        if(multifileDTO.getChunkSize() != null && multifileDTO.getChunkSize() <= 0) {
            throw new BaseException("分片大小不合法");
        }
        if(multifileDTO.getTotalChunks() != null && multifileDTO.getTotalChunks() <= 0) {
            throw new BaseException("分片数量不合法");
        }
    }

    /**
     * 规范化 normalizeParentId 相关逻辑。
     *
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private Long normalizeParentId(Long parentId, Long userId) {
        if(parentId == null || parentId == 0L) {
            return fileService.getRootId(userId);
        }
        if(!fileInfoMapper.ParentIdExist(parentId,userId)) {
            throw new BaseException("目标目录不存在或不属于当前用户");
        }
        return parentId;
    }

    private String normalizeUploadId(String uploadId) {
        if(uploadId == null || uploadId.isBlank()) {
            return UuidUtil.randomUuid();
        }
        if(!uploadId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new BaseException("上传任务 ID 格式不正确");
        }
        return uploadId;
    }

    private void validateResumeTask(UploadTask task,
                                    MultifileDTO multifileDTO,
                                    Long parentId,
                                    Long chunkSize,
                                    Integer totalChunks) {
        if(!UploadTaskConstant.UPLOADING.equals(task.getStatus()) && !UploadTaskConstant.FAIL.equals(task.getStatus())) {
            throw new BaseException("上传任务状态异常");
        }
        if(!Objects.equals(task.getParentId(),parentId)
                || !Objects.equals(task.getFileName(),multifileDTO.getFileName())
                || !Objects.equals(task.getFileSize(),multifileDTO.getFileSize())
                || !Objects.equals(task.getFileMd5(),multifileDTO.getFileMd5())
                || !Objects.equals(task.getFileSha1(),multifileDTO.getFileSha1())
                || !Objects.equals(task.getFileHash(),multifileDTO.getFileHash())
                || !Objects.equals(task.getChunkSize(),chunkSize)
                || !Objects.equals(task.getTotalChunks(),totalChunks)) {
            throw new BaseException("上传任务信息不匹配");
        }
    }

    private InitifileVO resumeExistingTask(UploadTask task, MultifileDTO dto, Long parentId) {
        if(UploadTaskConstant.MERGED.equals(task.getStatus())) {
            InitifileVO result = buildInitVO(task,true);
            result.setFile(mergedResult(task,task.getUserId()));
            return result;
        }
        if(UploadTaskConstant.MERGING.equals(task.getStatus())) {
            throw new ConflictException("文件正在合并，请稍后查询结果");
        }
        Long chunkSize = dto.getChunkSize() == null ? DEFAULT_CHUNK_SIZE : dto.getChunkSize();
        Integer totalChunks = dto.getTotalChunks() == null
                ? Math.toIntExact((dto.getFileSize() + chunkSize - 1) / chunkSize)
                : dto.getTotalChunks();
        validateResumeTask(task,dto,parentId,chunkSize,totalChunks);
        multifileMapper.touch(task.getUploadId());
        return buildInitVO(task,false);
    }

    private String uploadFileKey(Long userId, Long parentId, String fileName) {
        return CrossStoreOperationService.payloadHash(userId,parentId,fileName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private FileVO mergedResult(UploadTask task, Long userId) {
        UserFileDTO node = fileInfoMapper.getByFileUuidAndParent(task.getFileUuid(),task.getParentId(),userId);
        File file = fileInfoMapper.getFileInfo(task.getFileUuid(),userId);
        if(node == null || file == null) {
            throw new ConflictException("合并结果尚未完成落库，请稍后重试");
        }
        return toFileVO(file,node);
    }

    private void removePartsAfterCommit(List<String> objectNames) {
        Runnable cleanup = () -> minioclientUtil.removeFileParts(objectNames);
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    /**
     * 校验 requireUploadingTask 相关逻辑。
     *
     * @param uploadId 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    private UploadTask requireUploadingTask(String uploadId, Long userId) {
        if(uploadId == null || uploadId.isBlank()) {
            throw new BaseException("上传任务不能为空");
        }
        UploadTask task = multifileMapper.getByUploadId(uploadId,userId);
        if(task == null) {
            throw new BaseException("上传任务不存在");
        }
        if(!UploadTaskConstant.UPLOADING.equals(task.getStatus())) {
            throw new BaseException("上传任务状态异常");
        }
        return task;
    }

    /**
     * 校验 requireNoSameName 相关逻辑。
     *
     * @param fileName 文件名
     * @param parentId 父级 ID
     * @param userId 用户 ID
     */
    private void requireNoSameName(String fileName, Long parentId, Long userId) {
        File sameNameFile = fileInfoMapper.findFileByName(fileName,userId,parentId);
        if(sameNameFile != null) {
            throw new BaseException("目标目录已存在同名文件");
        }
    }

    /**
     * 校验 validateChunkSize 相关逻辑。
     *
     * @param file 文件对象
     * @param task 任务对象
     * @param chunkIndex 方法入参
     */
    private void validateChunkSize(MultipartFile file, UploadTask task, Integer chunkIndex) {
        long expectedSize = task.getChunkSize();
        if(chunkIndex.equals(task.getTotalChunks() - 1)) {
            long remainder = task.getFileSize() % task.getChunkSize();
            expectedSize = remainder == 0 ? task.getChunkSize() : remainder;
        }
        if(file.getSize() != expectedSize) {
            throw new BaseException("分片大小不正确");
        }
    }

    /**
     * 校验 validateChunkMd5 相关逻辑。
     *
     * @param file 文件对象
     * @param chunkMd5 方法入参
     */
    private void validateChunkMd5(MultipartFile file, String chunkMd5) {
        if(chunkMd5 == null || chunkMd5.isBlank()) {
            return;
        }
        try {
            String realMd5 = Md5Util.md5(file.getInputStream());
            if(!chunkMd5.equalsIgnoreCase(realMd5)) {
                throw new BaseException("分片校验失败");
            }
        } catch (Exception e) {
            if(e instanceof BaseException) {
                throw (BaseException) e;
            }
            throw new BaseException("分片校验失败");
        }
    }

    /**
     * 构建 buildInitVO 相关逻辑。
     *
     * @param uploadTask 方法入参
     * @param instantUpload 方法入参
     * @return 处理结果
     */
    private InitifileVO buildInitVO(UploadTask uploadTask, Boolean instantUpload) {
        InitifileVO initifileVO = new InitifileVO();
        initifileVO.setInstantUpload(instantUpload);
        initifileVO.setUploadId(uploadTask.getUploadId());
        initifileVO.setChunkSize(uploadTask.getChunkSize());
        initifileVO.setTotalChunks(uploadTask.getTotalChunks());
        initifileVO.setUploadedChunks(chunkUploadMapper.listUploadedIndexes(uploadTask.getUploadId()));
        return initifileVO;
    }

    /**
     * 执行 reuseExistingFile 函数的业务处理。
     *
     * @param existingFile 方法入参
     * @param fileName 文件名
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private FileVO reuseExistingFile(File existingFile, String fileName, Long parentId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UserFileDTO userFileDTO = UserFileDTO.builder()
                .fileUuid(existingFile.getFileUuid())
                .userId(userId)
                .fileName(fileName)
                .status(StatusConstant.ENABLE)
                .Dir(0)
                .parentId(parentId)
                .path(null)
                .createtime(now)
                .updatetime(now)
                .build();
        if(fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1) == 0) {
            throw new BaseException("文件引用计数更新失败");
        }
        fileInfoMapper.insertFile_User(userFileDTO);
        userFileDTO.setPath(buildPath(userFileDTO,userId));
        fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),userFileDTO.getPath(),userId);
        return toFileVO(existingFile,userFileDTO);
    }

    /**
     * 保存 saveMergedFile 相关逻辑。
     *
     * @param task 任务对象
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private FileVO saveMergedFile(UploadTask task, String fileUuid, Long parentId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        File file = File.builder()
                .fileUuid(fileUuid)
                .dir(false)
                .name(task.getFileName())
                .type(getFileType(task.getFileName()))
                .size(task.getFileSize())
                .md5(task.getFileMd5())
                .sha1(task.getFileSha1())
                .hash(task.getFileHash())
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        fileInfoMapper.insertFileInfo(file);

        UserFileDTO userFileDTO = UserFileDTO.builder()
                .fileUuid(fileUuid)
                .userId(userId)
                .fileName(task.getFileName())
                .status(StatusConstant.ENABLE)
                .Dir(0)
                .parentId(parentId)
                .path(null)
                .createtime(now)
                .updatetime(now)
                .build();
        fileInfoMapper.insertFile_User(userFileDTO);
        userFileDTO.setPath(buildPath(userFileDTO,userId));
        fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),userFileDTO.getPath(),userId);
        return toFileVO(file,userFileDTO);
    }

    /**
     * 构建 buildPath 相关逻辑。
     *
     * @param userFileDTO 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    private String buildPath(UserFileDTO userFileDTO, Long userId) {
        UserFileDTO parent = fileInfoMapper.getByFileId(userFileDTO.getParentId(),userId);
        String parentPath = parent == null || parent.getPath() == null ? "/" : parent.getPath();
        if(!parentPath.endsWith("/")) {
            parentPath = parentPath + "/";
        }
        return parentPath + userFileDTO.getFileName();
    }

    /**
     * 转换 toFileVO 相关逻辑。
     *
     * @param file 文件对象
     * @param userFileDTO 方法入参
     * @return 处理结果
     */
    private FileVO toFileVO(File file, UserFileDTO userFileDTO) {
        FileVO fileVO = new FileVO();
        fileVO.setFileId(userFileDTO.getId());
        fileVO.setFileUuid(userFileDTO.getFileUuid());
        fileVO.setDir(false);
        fileVO.setUserId(userFileDTO.getUserId());
        fileVO.setParentId(userFileDTO.getParentId());
        fileVO.setName(userFileDTO.getFileName());
        fileVO.setType(file.getType());
        fileVO.setSize(file.getSize());
        fileVO.setHash(file.getHash());
        fileVO.setCreateTime(userFileDTO.getCreatetime());
        fileVO.setUpdateTime(userFileDTO.getUpdatetime());
        return fileVO;
    }

    /**
     * 查询 getFileType 相关逻辑。
     *
     * @param fileName 文件名
     * @return 处理结果
     */
    private String getFileType(String fileName) {
        int dotIndex = fileName.lastIndexOf(".");
        if(dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return ".txt";
        }
        return fileName.substring(dotIndex);
    }
}
