package com.ylcloud.service;

import com.ylcloud.DTO.MultifileDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 初始化分片上传任务；如果完整文件 hash 已存在，则直接复用物理文件完成秒传。
     *
     * @param multifileDTO 分片上传初始化参数
     * @param userId 当前登录用户 ID
     * @return 初始化结果，包含上传任务 ID、秒传标记、分片大小和已上传分片列表
     */
    @Transactional
    public InitifileVO initfile(MultifileDTO multifileDTO, Long userId) {
        validateInitParam(multifileDTO);
        Long parentId = normalizeParentId(multifileDTO.getParentId(), userId);
        requireNoSameName(multifileDTO.getFileName(), parentId, userId);

        File existingFile = fileInfoMapper.getFileByHash(multifileDTO.getFileHash());
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

        UploadTask activeTask = multifileMapper.getActiveTask(userId,parentId,multifileDTO.getFileHash(),multifileDTO.getFileName());
        if(activeTask != null) {
            return buildInitVO(activeTask,false);
        }

        LocalDateTime now = LocalDateTime.now();
        UploadTask uploadTask = new UploadTask();
        uploadTask.setUploadId(UuidUtil.randomUuid());
        uploadTask.setUserId(userId);
        uploadTask.setParentId(parentId);
        uploadTask.setFileName(multifileDTO.getFileName());
        uploadTask.setFileSize(multifileDTO.getFileSize());
        uploadTask.setFileMd5(multifileDTO.getFileMd5());
        uploadTask.setFileHash(multifileDTO.getFileHash());
        uploadTask.setChunkSize(chunkSize);
        uploadTask.setTotalChunks(totalChunks);
        uploadTask.setUploadedChunks(0);
        uploadTask.setStatus(UploadTaskConstant.UPLOADING);
        uploadTask.setCreatetime(now);
        uploadTask.setUpdatetime(now);
        multifileMapper.insert(uploadTask);
        return buildInitVO(uploadTask,false);
    }

    /**
     * 上传单个分片。
     *
     * <p>该方法具备幂等性：如果同一个 uploadId + chunkIndex 已经存在有效记录，则直接返回成功。</p>
     *
     * @param file 当前分片文件
     * @param uploadId 上传任务 ID
     * @param chunkIndex 当前分片序号，从 0 开始
     * @param chunkMd5 当前分片 MD5，可为空；不为空时会校验分片内容
     * @param userId 当前登录用户 ID
     * @return 分片上传是否成功
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
            chunkUploadMapper.insert(uploadChunk);
            multifileMapper.increaseUploadedChunks(uploadId);
            return true;
        } catch (Exception e) {
            log.error("分片上传失败: uploadId={}, chunkIndex={}", uploadId, chunkIndex, e);
            throw new BaseException("分片上传失败");
        }
    }

    /**
     * 查询上传任务的断点续传进度。
     *
     * @param uploadId 上传任务 ID
     * @param userId 当前登录用户 ID
     * @return 上传进度信息，包含已上传分片序号列表
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
     * 合并指定上传任务下的所有分片，并写入 file_info 与 user_file。
     *
     * @param uploadId 上传任务 ID
     * @param userId 当前登录用户 ID
     * @return 合并完成后的文件信息
     */
    @Transactional
    public FileVO merge(String uploadId, Long userId) {
        UploadTask task = requireUploadingTask(uploadId,userId);
        List<Integer> uploadedIndexes = chunkUploadMapper.listUploadedIndexes(uploadId);
        if(uploadedIndexes.size() != task.getTotalChunks()) {
            throw new BaseException("分片未上传完整");
        }
        Long parentId = normalizeParentId(task.getParentId(),userId);
        requireNoSameName(task.getFileName(),parentId,userId);

        String fileUuid = UuidUtil.randomUuid();
        List<String> objectNames = chunkUploadMapper.listObjectNames(uploadId);
        FileMergeReqVO mergeReqVO = new FileMergeReqVO();
        mergeReqVO.setUploadId(uploadId);
        mergeReqVO.setFileUuid(fileUuid);
        mergeReqVO.setFileName(task.getFileName());
        mergeReqVO.setPartNames(objectNames);

        try {
            minioclientUtil.mergeFileParts(mergeReqVO);
            FileVO fileVO = saveMergedFile(task,fileUuid,parentId,userId);
            multifileMapper.markMerged(uploadId,fileUuid);
            return fileVO;
        } catch (Exception e) {
            multifileMapper.markFail(uploadId);
            log.error("分片合并失败: {}", uploadId, e);
            throw new BaseException("分片合并失败");
        }
    }

    /**
     * 校验初始化上传任务参数。
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
        if(multifileDTO.getFileSize() > maxFileSize) {
            throw new BaseException("文件大小超过限制");
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
     * 规范化父目录 ID；当父目录为空或 0 时返回当前用户根目录。
     *
     * @param parentId 请求传入的父目录 ID
     * @param userId 当前登录用户 ID
     * @return 可用的父目录 ID
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

    /**
     * 查询并校验上传任务必须存在且处于上传中状态。
     *
     * @param uploadId 上传任务 ID
     * @param userId 当前登录用户 ID
     * @return 上传中的任务实体
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
     * 校验目标目录下不存在同名文件。
     *
     * @param fileName 文件名
     * @param parentId 父目录 ID
     * @param userId 当前登录用户 ID
     */
    private void requireNoSameName(String fileName, Long parentId, Long userId) {
        File sameNameFile = fileInfoMapper.findFileByName(fileName,userId,parentId);
        if(sameNameFile != null) {
            throw new BaseException("目标目录已存在同名文件");
        }
    }

    /**
     * 校验分片大小是否符合上传任务定义。
     *
     * @param file 当前分片文件
     * @param task 上传任务实体
     * @param chunkIndex 当前分片序号
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
     * 校验分片 MD5；未传入 MD5 时跳过校验。
     *
     * @param file 当前分片文件
     * @param chunkMd5 前端传入的分片 MD5
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
     * 根据上传任务组装初始化响应结果。
     *
     * @param uploadTask 上传任务实体
     * @param instantUpload 是否秒传成功
     * @return 初始化响应结果
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
     * 复用已存在的物理文件，创建当前用户目录下的 user_file 记录，实现秒传。
     *
     * @param existingFile 已存在的物理文件元数据
     * @param fileName 当前用户目录中展示的文件名
     * @param parentId 父目录 ID
     * @param userId 当前登录用户 ID
     * @return 秒传后的文件信息
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
        fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1);
        fileInfoMapper.insertFile_User(userFileDTO);
        userFileDTO.setPath(buildPath(userFileDTO,userId));
        fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),userFileDTO.getPath(),userId);
        return toFileVO(existingFile,userFileDTO);
    }

    /**
     * 保存分片合并后的文件元数据和用户文件关系。
     *
     * @param task 上传任务实体
     * @param fileUuid 合并后最终文件 UUID
     * @param parentId 父目录 ID
     * @param userId 当前登录用户 ID
     * @return 合并后的文件信息
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
     * 根据父目录路径构造当前文件路径。
     *
     * @param userFileDTO 用户文件关系实体
     * @param userId 当前登录用户 ID
     * @return 当前文件路径
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
     * 将物理文件信息和用户文件关系合并为前端展示对象。
     *
     * @param file 物理文件元数据
     * @param userFileDTO 用户文件关系
     * @return 文件展示对象
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
     * 根据文件名解析文件后缀。
     *
     * @param fileName 文件名
     * @return 文件类型后缀；无后缀时返回 .txt
     */
    private String getFileType(String fileName) {
        int dotIndex = fileName.lastIndexOf(".");
        if(dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return ".txt";
        }
        return fileName.substring(dotIndex);
    }
}
