package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.DTO.SpaceFileImportDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.MinioclientUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 空间文件树业务服务。
 */
@Service
public class SpaceFileService {
    private static final long MAX_TEXT_PREVIEW_SIZE = 1024 * 1024;

    private final SpaceFileMapper spaceFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceService spaceService;
    private final SpacePermissionService spacePermissionService;
    private final SpaceRagService spaceRagService;
    private final MinioclientUtil minioclientUtil;

    /**
     * 初始化 SpaceFileService 对象。
     *
     * @param spaceFileMapper 方法入参
     * @param fileInfoMapper 方法入参
     * @param spaceService 空间服务
     * @param spacePermissionService 方法入参
     * @param spaceRagService 空间 RAG 服务
     * @param minioclientUtil 方法入参
     */
    public SpaceFileService(SpaceFileMapper spaceFileMapper,
                            FileInfoMapper fileInfoMapper,
                            SpaceService spaceService,
                            SpacePermissionService spacePermissionService,
                            SpaceRagService spaceRagService,
                            MinioclientUtil minioclientUtil) {
        this.spaceFileMapper = spaceFileMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.spaceService = spaceService;
        this.spacePermissionService = spacePermissionService;
        this.spaceRagService = spaceRagService;
        this.minioclientUtil = minioclientUtil;
    }

    /**
     * 查询 listFiles 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> listFiles(Long spaceId, Long parentId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        Long realParentId = normalizeParentId(spaceId,parentId);
        return toVOList(spaceFileMapper.listByParentId(spaceId,realParentId));
    }

    /**
     * 执行 tree 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> tree(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        Long rootId = normalizeParentId(spaceId,null);
        SpaceFile root = spaceFileMapper.getById(spaceId,rootId);
        SpaceFileVO rootVO = toVO(root);
        rootVO.setChildren(buildChildren(spaceId,rootId));
        return List.of(rootVO);
    }

    /**
     * 创建 createFolder 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO createFolder(Long spaceId, SpaceFolderCreateDTO dto, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        String folderName = requireSafeFileName(dto.getName());
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        requireNoSameName(spaceId,parentId,folderName,1);

        LocalDateTime now = LocalDateTime.now();
        SpaceFile folder = new SpaceFile();
        folder.setSpaceId(spaceId);
        folder.setFileName(folderName);
        folder.setDir(1);
        folder.setParentId(parentId);
        folder.setPath(buildPath(parent,folderName,true));
        folder.setStatus(StatusConstant.ENABLE);
        folder.setCreatedBy(userId);
        folder.setCreatetime(now);
        folder.setUpdatetime(now);
        spaceFileMapper.insert(folder);
        return toVO(folder);
    }

    /**
     * 执行 importUserFile 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO importUserFile(Long spaceId, SpaceFileImportDTO dto, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        UserFileDTO userFile = fileInfoMapper.getByFileId(dto.getUserFileId(),userId);
        if(userFile == null || userFile.getDir() == 1) {
            throw new BaseException("只能导入当前用户可读取的文件");
        }
        String fileName = dto.getName() == null || dto.getName().isBlank() ? userFile.getFileName() : dto.getName();
        fileName = requireSafeFileName(fileName);
        requireNoSameName(spaceId,parentId,fileName,0);

        LocalDateTime now = LocalDateTime.now();
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setSpaceId(spaceId);
        spaceFile.setFileUuid(userFile.getFileUuid());
        spaceFile.setFileName(fileName);
        spaceFile.setDir(0);
        spaceFile.setParentId(parentId);
        spaceFile.setPath(buildPath(parent,fileName,false));
        spaceFile.setStatus(StatusConstant.ENABLE);
        spaceFile.setCreatedBy(userId);
        spaceFile.setCreatetime(now);
        spaceFile.setUpdatetime(now);
        spaceFileMapper.insert(spaceFile);
        if(fileInfoMapper.updateFileCount(userFile.getFileUuid(),1) == 0) {
            throw new BaseException("文件引用计数更新失败");
        }
        spaceRagService.handleFileImported(spaceFile,userId);
        return toVO(spaceFile);
    }

    /**
     * 移除 removeFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean removeFile(Long spaceId, Long fileId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceFile file = spaceFileMapper.getById(spaceId,fileId);
        if(file == null) {
            throw new BaseException("空间文件不存在");
        }
        if(file.getParentId() == 0L) {
            throw new BaseException("不能删除空间根目录");
        }
        removeTree(spaceId,file,userId);
        return true;
    }

    private void removeTree(Long spaceId, SpaceFile file, Long userId) {
        if(file.getDir() == 1) {
            for(SpaceFile child : spaceFileMapper.listByParentId(spaceId,file.getId())) {
                removeTree(spaceId,child,userId);
            }
        }
        int rows = spaceFileMapper.disable(spaceId,file.getId(),LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("空间文件删除失败");
        }
        if(file.getDir() == 0 && file.getFileUuid() != null) {
            if(fileInfoMapper.updateFileCount(file.getFileUuid(),-1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            spaceRagService.handleFileRemoved(spaceId,file.getId(),userId);
        }
    }

    /**
     * 预览 previewFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public FilePreviewVO previewFile(Long spaceId, Long fileId, Long userId) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        String contentType = resolveContentType(spaceFile.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,spaceFile.getFileName());
        FilePreviewVO vo = new FilePreviewVO();
        vo.setFileUuid(spaceFile.getFileUuid());
        vo.setName(spaceFile.getFileName());
        vo.setPreviewType(previewType);
        vo.setContentType(contentType);
        vo.setSize(file.getSize());
        if("text".equals(previewType)) {
            vo.setTextContent(readTextPreview(file));
            return vo;
        }
        if(isStreamPreviewType(previewType)) {
            vo.setPreviewUrl("/api/space/" + spaceId + "/files/" + fileId + "/preview/stream");
            return vo;
        }
        throw new BaseException("当前文件类型不支持预览");
    }

    /**
     * 预览 previewFileStream 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void previewFileStream(Long spaceId, Long fileId, Long userId, HttpServletResponse response) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        String contentType = resolveContentType(spaceFile.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,spaceFile.getFileName());
        if("text".equals(previewType)) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        if(!isStreamPreviewType(previewType) && !"text".equals(previewType)) {
            throw new BaseException("当前文件类型不支持预览");
        }
        try {
            minioclientUtil.previewObject(spaceFile.getFileUuid(),spaceFile.getFileName(),contentType,response);
        } catch (Exception e) {
            throw new RuntimeException("空间文件预览失败",e);
        }
    }

    /**
     * 下载 downloadFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void downloadFile(Long spaceId, Long fileId, Long userId, HttpServletResponse response) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        FileDTO fileDTO = new FileDTO();
        fileDTO.setFileUuid(file.getFileUuid());
        fileDTO.setName(spaceFile.getFileName());
        fileDTO.setType(file.getType());
        fileDTO.setSize(file.getSize());
        fileDTO.setHash(file.getHash());
        fileDTO.setMd5(file.getMd5());
        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            throw new RuntimeException("空间文件下载失败",e);
        }
    }

    /**
     * 规范化 normalizeParentId 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    private Long normalizeParentId(Long spaceId, Long parentId) {
        if(parentId != null && parentId != 0L) {
            return parentId;
        }
        Space space = spaceService.requireSpace(spaceId);
        if(space.getRootDirId() == null) {
            throw new BaseException("空间根目录不存在");
        }
        return space.getRootDirId();
    }

    /**
     * 校验 requireDirectory 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @return 处理结果
     */
    private SpaceFile requireDirectory(Long spaceId, Long fileId) {
        SpaceFile file = spaceFileMapper.getById(spaceId,fileId);
        if(file == null || file.getDir() != 1) {
            throw new BaseException("目标目录不存在");
        }
        return file;
    }

    /**
     * 校验 requirePreviewableSpaceFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private SpaceFile requirePreviewableSpaceFile(Long spaceId, Long fileId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceFile spaceFile = spaceFileMapper.getById(spaceId,fileId);
        if(spaceFile == null || spaceFile.getDir() == 1) {
            throw new BaseException("空间文件不存在或不是普通文件");
        }
        if(spaceFile.getFileUuid() == null || spaceFile.getFileUuid().isBlank()) {
            throw new BaseException("空间文件缺少物理文件标识");
        }
        return spaceFile;
    }

    /**
     * 校验 requireFileInfo 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    private File requireFileInfo(SpaceFile spaceFile) {
        File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        if(file == null) {
            throw new BaseException("文件元数据不存在");
        }
        return file;
    }

    /**
     * 执行 readTextPreview 函数的业务处理。
     *
     * @param file 文件对象
     * @return 处理结果
     */
    private String readTextPreview(File file) {
        if(file.getSize() != null && file.getSize() > MAX_TEXT_PREVIEW_SIZE) {
            throw new BaseException("文本文件过大，不支持直接预览");
        }
        try (InputStream inputStream = minioclientUtil.getObjectStream(file.getFileUuid())) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("文本预览失败",e);
        }
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

    /**
     * 校验 requireNoSameName 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @param fileName 文件名
     * @param dir 方法入参
     */
    private void requireNoSameName(Long spaceId, Long parentId, String fileName, Integer dir) {
        if(spaceFileMapper.countSameName(spaceId,parentId,fileName,dir) > 0) {
            throw new BaseException("目标目录已存在同名节点");
        }
    }

    /**
     * 构建 buildChildren 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @return 列表结果
     */
    private List<SpaceFileVO> buildChildren(Long spaceId, Long parentId) {
        List<SpaceFileVO> children = new ArrayList<>();
        for(SpaceFile child : spaceFileMapper.listByParentId(spaceId,parentId)) {
            SpaceFileVO vo = toVO(child);
            if(child.getDir() == 1) {
                vo.setChildren(buildChildren(spaceId,child.getId()));
            }
            children.add(vo);
        }
        return children;
    }

    /**
     * 转换 toVOList 相关逻辑。
     *
     * @param files 方法入参
     * @return 列表结果
     */
    private List<SpaceFileVO> toVOList(List<SpaceFile> files) {
        List<SpaceFileVO> result = new ArrayList<>();
        files.forEach(file -> result.add(toVO(file)));
        return result;
    }

    /**
     * 转换 toVO 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    private SpaceFileVO toVO(SpaceFile spaceFile) {
        SpaceFileVO vo = new SpaceFileVO();
        vo.setId(spaceFile.getId());
        vo.setSpaceId(spaceFile.getSpaceId());
        vo.setFileUuid(spaceFile.getFileUuid());
        vo.setName(spaceFile.getFileName());
        vo.setDir(spaceFile.getDir() == 1);
        vo.setParentId(spaceFile.getParentId());
        vo.setPath(spaceFile.getPath());
        vo.setVersionEnabled(spaceFile.getVersionEnabled());
        vo.setEffectiveVersionEnabled(resolveEffectiveVersionEnabled(spaceFile));
        vo.setCreatetime(spaceFile.getCreatetime());
        vo.setUpdatetime(spaceFile.getUpdatetime());
        if(spaceFile.getDir() == 0 && spaceFile.getFileUuid() != null) {
            File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
            if(file != null) {
                vo.setType(file.getType());
                vo.setSize(file.getSize());
            }
        } else {
            vo.setType("dir");
        }
        return vo;
    }

    /**
     * 更新 updateVersionEnabled 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionEnabled 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO updateVersionEnabled(Long spaceId, Long fileId, Integer versionEnabled, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        if(versionEnabled != null && !StatusConstant.ENABLE.equals(versionEnabled) && !StatusConstant.DISABLE.equals(versionEnabled)) {
            throw new BaseException("文件历史版本开关只能为 1、0 或 null");
        }
        SpaceFile file = spaceFileMapper.getById(spaceId,fileId);
        if(file == null || file.getDir() == 1) {
            throw new BaseException("空间文件不存在或不是普通文件");
        }
        int rows = spaceFileMapper.updateVersionEnabled(spaceId,fileId,versionEnabled,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件历史版本设置更新失败");
        }
        return toVO(spaceFileMapper.getById(spaceId,fileId));
    }

    /**
     * 查询 listVersionEnabledFiles 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> listVersionEnabledFiles(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        List<SpaceFileVO> result = new ArrayList<>();
        for(SpaceFile file : spaceFileMapper.listAll(spaceId)) {
            if(file.getDir() == 0 && resolveEffectiveVersionEnabled(file)) {
                result.add(toVO(file));
            }
        }
        return result;
    }

    /**
     * 解析 resolveEffectiveVersionEnabled 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    public Boolean resolveEffectiveVersionEnabled(SpaceFile spaceFile) {
        if(spaceFile.getVersionEnabled() != null) {
            return StatusConstant.ENABLE.equals(spaceFile.getVersionEnabled());
        }
        Space space = spaceService.requireSpace(spaceFile.getSpaceId());
        return StatusConstant.ENABLE.equals(space.getVersionEnabled());
    }

    private String requireSafeFileName(String fileName) {
        if(fileName == null) {
            throw new BaseException("文件名不能为空");
        }
        String normalized = fileName.trim();
        if(normalized.isEmpty() || normalized.length() > 255) {
            throw new BaseException("文件名不能为空且不能超过 255 个字符");
        }
        if(normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new BaseException("文件名包含非法路径字符");
        }
        return normalized;
    }

    /**
     * 构建 buildPath 相关逻辑。
     *
     * @param parent 方法入参
     * @param name 名称
     * @param dir 方法入参
     * @return 处理结果
     */
    private String buildPath(SpaceFile parent, String name, boolean dir) {
        String parentPath = parent.getPath() == null ? "/" : parent.getPath();
        if(!parentPath.endsWith("/")) {
            parentPath = parentPath + "/";
        }
        String path = parentPath + name;
        return dir ? path + "/" : path;
    }
}
