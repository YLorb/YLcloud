package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.DTO.SpaceFileImportDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.SpaceWebLinkImportDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.UuidUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 空间文件树业务服务。
 */
@Service
public class SpaceFileService {
    private static final long MAX_TEXT_PREVIEW_SIZE = 1024 * 1024;
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final SpaceFileMapper spaceFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceService spaceService;
    private final SpacePermissionService spacePermissionService;
    private final SpaceRagService spaceRagService;
    private final MinioclientUtil minioclientUtil;
    private final SiteSettingService siteSettingService;
    private final PhysicalFileCleanupService physicalFileCleanupService;
    private final InitialFileVersionService initialFileVersionService;

    @Value("${ylcloud.upload.max-file-size:2147483648}")
    private Long maxFileSize;

    @Value("${ylcloud.rag.web-link.max-size:5242880}")
    private Long maxWebLinkSize;

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
                            MinioclientUtil minioclientUtil,
                            SiteSettingService siteSettingService,
                            PhysicalFileCleanupService physicalFileCleanupService,
                            InitialFileVersionService initialFileVersionService) {
        this.spaceFileMapper = spaceFileMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.spaceService = spaceService;
        this.spacePermissionService = spacePermissionService;
        this.spaceRagService = spaceRagService;
        this.minioclientUtil = minioclientUtil;
        this.siteSettingService = siteSettingService;
        this.physicalFileCleanupService = physicalFileCleanupService;
        this.initialFileVersionService = initialFileVersionService;
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
        UserFileDTO userFile = fileInfoMapper.getUserFileByPhysicalFileId(dto.getUserFileId(),userId);
        if(userFile == null) {
            userFile = fileInfoMapper.getByFileId(dto.getUserFileId(),userId);
        }
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
        ensureInitialVersionIfEnabled(spaceFile,userId);
        spaceRagService.handleFileImported(spaceFile,userId);
        return toVO(spaceFile);
    }

    /**
     * 上传本地文件到空间。
     *
     * @param spaceId 空间 ID
     * @param uploadFile 上传文件
     * @param parentId 父级目录 ID
     * @param name 可选覆盖文件名
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO uploadFile(Long spaceId, MultipartFile uploadFile, Long parentId, String name, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        validateUploadFile(uploadFile);
        String fileName = name == null || name.isBlank() ? uploadFile.getOriginalFilename() : name;
        fileName = requireSafeFileName(fileName);
        Long realParentId = normalizeParentId(spaceId,parentId);
        SpaceFile parent = requireDirectory(spaceId,realParentId);
        requireNoSameName(spaceId,realParentId,fileName,0);

        StoredPhysicalFile stored = storeMultipartFile(uploadFile,fileName);
        SpaceFile spaceFile = createSpaceFile(spaceId,parent,stored.fileUuid(),fileName,userId);
        ensureInitialVersionIfEnabled(spaceFile,userId);
        spaceRagService.handleFileImported(spaceFile,userId);
        return toVO(spaceFile);
    }

    /**
     * 抓取网页链接并作为 Markdown 文档导入空间。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO importWebLink(Long spaceId, SpaceWebLinkImportDTO dto, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        URI uri = requireHttpUri(dto.getUrl());
        WebPageSnapshot snapshot = fetchWebPage(uri);
        String fileName = dto.getName() == null || dto.getName().isBlank() ? defaultLinkFileName(snapshot.title(),uri) : dto.getName();
        fileName = requireSafeFileName(ensureMarkdownExtension(fileName));
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        requireNoSameName(spaceId,parentId,fileName,0);

        String markdown = buildWebLinkMarkdown(uri,snapshot);
        StoredPhysicalFile stored = storeGeneratedFile(fileName,markdown.getBytes(StandardCharsets.UTF_8),"text/markdown;charset=UTF-8");
        SpaceFile spaceFile = createSpaceFile(spaceId,parent,stored.fileUuid(),fileName,userId);
        ensureInitialVersionIfEnabled(spaceFile,userId);
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
            throw new NotFoundException("空间文件不存在");
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
            if(fileInfoMapper.getFileCount(file.getFileUuid()) == 0) {
                physicalFileCleanupService.enqueue(file.getFileUuid());
            }
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
            throw new NotFoundException("目标目录不存在");
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
            throw new NotFoundException("空间文件不存在或不是普通文件");
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
            throw new NotFoundException("文件元数据不存在");
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
            throw new ConflictException("目标目录已存在同名节点");
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
            throw new NotFoundException("空间文件不存在或不是普通文件");
        }
        int rows = spaceFileMapper.updateVersionEnabled(spaceId,fileId,versionEnabled,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件历史版本设置更新失败");
        }
        SpaceFile updated = spaceFileMapper.getById(spaceId,fileId);
        ensureInitialVersionIfEnabled(updated,userId);
        return toVO(updated);
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

    private void ensureInitialVersionIfEnabled(SpaceFile spaceFile, Long userId) {
        if(spaceFile != null && spaceFile.getDir() == 0 && resolveEffectiveVersionEnabled(spaceFile)) {
            initialFileVersionService.ensureInitialVersion(spaceFile.getFileUuid(),spaceFile.getFileName(),userId);
        }
    }

    private StoredPhysicalFile storeMultipartFile(MultipartFile uploadFile, String fileName) {
        String md5;
        String sha1;
        String hash;
        try {
            md5 = Md5Util.md5(uploadFile.getInputStream());
            sha1 = HashUtil.sha1(uploadFile.getInputStream());
            hash = HashUtil.sha256(uploadFile.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("文件哈希计算失败",e);
        }

        File existingFile = fileInfoMapper.getFileByHash(hash);
        if(existingFile != null) {
            if(fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            return new StoredPhysicalFile(existingFile.getFileUuid());
        }

        String fileUuid = UuidUtil.randomUuid();
        LocalDateTime now = LocalDateTime.now();
        File file = File.builder()
                .fileUuid(fileUuid)
                .dir(false)
                .name(fileName)
                .type(getFileType(fileName))
                .size(uploadFile.getSize())
                .hash(hash)
                .md5(md5)
                .sha1(sha1)
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            minioclientUtil.putObject(uploadFile,fileUuid);
        } catch (Exception e) {
            throw new RuntimeException("空间文件上传失败",e);
        }
        if(fileInfoMapper.insertFileInfo(file) == 0) {
            throw new BaseException("文件元数据保存失败");
        }
        return new StoredPhysicalFile(fileUuid);
    }

    private StoredPhysicalFile storeGeneratedFile(String fileName, byte[] content, String contentType) {
        if(content == null || content.length == 0) {
            throw new BaseException("文件内容不能为空");
        }
        if(content.length > siteSettingService.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }

        String md5;
        String sha1;
        String hash;
        try {
            md5 = Md5Util.md5(new ByteArrayInputStream(content));
            sha1 = HashUtil.sha1(new ByteArrayInputStream(content));
            hash = HashUtil.sha256(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new RuntimeException("文件哈希计算失败",e);
        }

        File existingFile = fileInfoMapper.getFileByHash(hash);
        if(existingFile != null) {
            if(fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            return new StoredPhysicalFile(existingFile.getFileUuid());
        }

        String fileUuid = UuidUtil.randomUuid();
        LocalDateTime now = LocalDateTime.now();
        File file = File.builder()
                .fileUuid(fileUuid)
                .dir(false)
                .name(fileName)
                .type(getFileType(fileName))
                .size((long) content.length)
                .hash(hash)
                .md5(md5)
                .sha1(sha1)
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            minioclientUtil.putObject(new ByteArrayInputStream(content),content.length,contentType,fileUuid);
        } catch (Exception e) {
            throw new RuntimeException("空间文件上传失败",e);
        }
        if(fileInfoMapper.insertFileInfo(file) == 0) {
            throw new BaseException("文件元数据保存失败");
        }
        return new StoredPhysicalFile(fileUuid);
    }

    private SpaceFile createSpaceFile(Long spaceId, SpaceFile parent, String fileUuid, String fileName, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setSpaceId(spaceId);
        spaceFile.setFileUuid(fileUuid);
        spaceFile.setFileName(fileName);
        spaceFile.setDir(0);
        spaceFile.setParentId(parent.getId());
        spaceFile.setPath(buildPath(parent,fileName,false));
        spaceFile.setStatus(StatusConstant.ENABLE);
        spaceFile.setCreatedBy(userId);
        spaceFile.setCreatetime(now);
        spaceFile.setUpdatetime(now);
        if(spaceFileMapper.insert(spaceFile) == 0) {
            throw new BaseException("空间文件保存失败");
        }
        return spaceFile;
    }

    private void validateUploadFile(MultipartFile uploadFile) {
        if(uploadFile == null || uploadFile.isEmpty()) {
            throw new BaseException("上传文件不能为空");
        }
        if(uploadFile.getSize() > siteSettingService.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }
        requireSafeFileName(uploadFile.getOriginalFilename());
    }

    private URI requireHttpUri(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if(scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new BaseException("只支持 http 或 https 链接");
            }
            if(uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BaseException("网页链接缺少主机名");
            }
            requirePublicHost(uri.getHost());
            return uri;
        } catch (IllegalArgumentException e) {
            throw new BaseException("网页链接格式不正确");
        }
    }

    private void requirePublicHost(String host) {
        try {
            for(InetAddress address : InetAddress.getAllByName(host)) {
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new BaseException("不允许导入内网或本机地址");
                }
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException("网页链接主机名解析失败");
        }
    }

    private WebPageSnapshot fetchWebPage(URI uri) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent","YLCloud-RAG-Link-Importer/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if(status < 200 || status >= 300) {
                throw new BaseException("网页请求失败，状态码 " + status);
            }
            String contentType = response.headers().firstValue("content-type").orElse("text/plain");
            if(!isTextualContentType(contentType)) {
                throw new BaseException("网页链接内容不是可索引文本");
            }
            byte[] body = readLimited(response.body(),maxWebLinkSize);
            String raw = new String(body,resolveCharset(contentType));
            String title = extractTitle(raw,uri);
            String text = contentType.toLowerCase().contains("html") ? htmlToText(raw) : raw;
            return new WebPageSnapshot(title,contentType,text);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("网页链接导入失败",e);
        }
    }

    private boolean isTextualContentType(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase();
        return lower.startsWith("text/")
                || lower.contains("application/json")
                || lower.contains("application/xml")
                || lower.contains("application/xhtml+xml")
                || lower.contains("application/javascript")
                || lower.contains("application/x-ndjson");
    }

    private byte[] readLimited(InputStream inputStream, long maxBytes) throws IOException {
        try(inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while((read = inputStream.read(buffer)) != -1) {
                total += read;
                if(total > maxBytes) {
                    throw new BaseException("网页内容超过导入大小限制");
                }
                output.write(buffer,0,read);
            }
            return output.toByteArray();
        }
    }

    private java.nio.charset.Charset resolveCharset(String contentType) {
        if(contentType != null) {
            String lower = contentType.toLowerCase();
            int index = lower.indexOf("charset=");
            if(index >= 0) {
                String charset = contentType.substring(index + 8).trim().replace("\"","");
                try {
                    return java.nio.charset.Charset.forName(charset);
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String extractTitle(String raw, URI uri) {
        Matcher matcher = TITLE_PATTERN.matcher(raw == null ? "" : raw);
        if(matcher.find()) {
            String title = htmlToText(matcher.group(1)).trim();
            if(!title.isBlank()) {
                return title;
            }
        }
        return uri.getHost();
    }

    private String htmlToText(String html) {
        if(html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>"," ")
                .replaceAll("(?is)<style[^>]*>.*?</style>"," ")
                .replaceAll("(?i)<br\\s*/?>","\n")
                .replaceAll("(?i)</p>","\n")
                .replaceAll("(?i)</h[1-6]>","\n")
                .replaceAll("(?is)<[^>]+>"," ")
                .replace("&nbsp;"," ")
                .replace("&amp;","&")
                .replace("&lt;","<")
                .replace("&gt;",">")
                .replace("&quot;","\"")
                .replace("&#39;","'")
                .replaceAll("[ \\t\\x0B\\f\\r]+"," ")
                .replaceAll("\\n\\s+","\n")
                .replaceAll("\\n{3,}","\n\n")
                .trim();
    }

    private String defaultLinkFileName(String title, URI uri) {
        String base = title == null || title.isBlank() ? uri.getHost() : title;
        return sanitizeFileName(base) + ".md";
    }

    private String sanitizeFileName(String value) {
        String sanitized = value == null ? "web-link" : value.replaceAll("[\\\\/:*?\"<>|]"," ").replaceAll("\\s+"," ").trim();
        if(sanitized.isBlank()) {
            return "web-link";
        }
        return sanitized.length() > 120 ? sanitized.substring(0,120).trim() : sanitized;
    }

    private String ensureMarkdownExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt") ? fileName : fileName + ".md";
    }

    private String buildWebLinkMarkdown(URI uri, WebPageSnapshot snapshot) {
        return "# " + snapshot.title() + "\n\n"
                + "- Source: " + uri + "\n"
                + "- Content-Type: " + snapshot.contentType() + "\n"
                + "- Imported-At: " + LocalDateTime.now() + "\n\n"
                + snapshot.text() + "\n";
    }

    private String getFileType(String fileName) {
        if(fileName == null) {
            return ".txt";
        }
        int dotIndex = fileName.lastIndexOf(".");
        if(dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return ".txt";
        }
        return fileName.substring(dotIndex);
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

    private record StoredPhysicalFile(String fileUuid) {}

    private record WebPageSnapshot(String title, String contentType, String text) {}

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
