package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.DTO.FileDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.ShareFileVO;
import com.ylcloud.constant.NameConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileShare;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileShareMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.ShareCodeUtil;
import com.ylcloud.utils.UuidUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@Service
@Slf4j
public class FileService {
    private static final long MAX_TEXT_PREVIEW_SIZE = 1024 * 1024;

    @Autowired
    private FileInfoMapper fileInfoMapper;
    @Autowired
    private FileShareMapper fileShareMapper;
    @Autowired
    private LoginMapper loginMapper;
    @Autowired
    private MinioclientUtil minioclientUtil;

    @Value("${ylcloud.upload.max-file-size:2147483648}")
    private Long maxFileSize;

    private enum FilePermission {
        READ,
        WRITE,
        MODIFY,
        DELETE
    }

    /**
     * 转换 toFileVO 相关逻辑。
     *
     * @param file 文件对象
     * @return 处理结果
     */
    private FileVO toFileVO(File file) {
        FileVO fileVO = new FileVO();
        BeanUtils.copyProperties(file,fileVO);
        return fileVO;
    }

    /**
     * 转换 toFileVO 相关逻辑。
     *
     * @param fileDTO 方法入参
     * @return 处理结果
     */
    private FileVO toFileVO(FileDTO fileDTO) {
        FileVO fileVO = new FileVO();
        BeanUtils.copyProperties(fileDTO,fileVO);
        return fileVO;
    }

    /**
     * 转换 toFileVO 相关逻辑。
     *
     * @param userFileDTO 方法入参
     * @return 处理结果
     */
    private FileVO toFileVO(UserFileDTO userFileDTO) {
        FileVO fileVO = new FileVO();
        fileVO.setFileId(userFileDTO.getId());
        fileVO.setFileUuid(userFileDTO.getFileUuid());
        fileVO.setDir(userFileDTO.getDir() == 1);
        fileVO.setUserId(userFileDTO.getUserId());
        fileVO.setParentId(userFileDTO.getParentId());
        fileVO.setName(userFileDTO.getFileName());
        fileVO.setCreateTime(userFileDTO.getCreatetime());
        fileVO.setUpdateTime(userFileDTO.getUpdatetime());
        if(userFileDTO.getDir() == 0) {
            File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
            if(file != null) {
                fileVO.setType(file.getType());
                fileVO.setSize(file.getSize());
                fileVO.setHash(file.getHash());
            }
        } else {
            fileVO.setType("dir");
        }
        return fileVO;
    }

    /**
     * 执行 file_Status 函数的业务处理。
     *
     * @param fileUuid 文件 UUID
     * @return 处理结果
     */
    private boolean file_Status(String fileUuid) {
        return fileInfoMapper.getFileStatus(fileUuid);
    }

    /**
     * 执行 userFileAvailable 函数的业务处理。
     *
     * @param userFileDTO 方法入参
     * @return 处理结果
     */
    private boolean userFileAvailable(UserFileDTO userFileDTO) {
        if(userFileDTO == null || userFileDTO.getStatus() == StatusConstant.DISABLE) {
            return false;
        }
        return userFileDTO.getDir() == 1 || file_Status(userFileDTO.getFileUuid());
    }

    /**
     * 执行 Admin 函数的业务处理。
     *
     * @param user 方法入参
     * @return 处理结果
     */
    private boolean Admin(User user) {
        //return user != null && ("ADMIN".equalsIgnoreCase(user.getRole()) || "admin".equalsIgnoreCase(user.getUsername()));
        return user != null && ("ADMIN".equalsIgnoreCase(user.getRole())); // fixed：防止越权
    }

    /**
     * 执行 currentUser 函数的业务处理。
     * @return 处理结果
     */
    private User currentUser() {
        User user = loginMapper.getById(BaseContext.getCurrentId());
        if(user == null) {
            throw new BaseException("用户不存在或登录状态无效");
        }
        return user;
    }

    /**
     * 校验 requirePermission 相关逻辑。
     *
     * @param userFileDTO 方法入参
     * @param permission 方法入参
     */
    private void requirePermission(UserFileDTO userFileDTO, FilePermission permission) {
        if(userFileDTO == null || userFileDTO.getStatus() == StatusConstant.DISABLE) {
            throw new BaseException("文件不存在或已失效");
        }
        User user = currentUser();
        if(Admin(user) || user.getId().equals(userFileDTO.getUserId())) {
            return;
        }
        throw new BaseException("没有文件" + permissionName(permission) + "权限");
    }

    /**
     * 把 FilePermission 枚举值转换成对应的中文名称。
     *
     * @param permission 方法入参
     * @return 处理结果
     */
    private String permissionName(FilePermission permission) {
        return switch (permission) {
            case READ -> "读取";
            case WRITE -> "写入";
            case MODIFY -> "修改";
            case DELETE -> "删除";
        };
    }

    /**
     * 校验所有权并获取文件（通过 fileId 获取文件）
     *
     * @param fileId 文件 ID
     * @param permission 方法入参
     * @return 处理结果
     */
    private UserFileDTO requireFileById(Long fileId, FilePermission permission) {
        User user = currentUser();
        UserFileDTO userFileDTO = Admin(user) ? fileInfoMapper.getByFileIdAny(fileId) : fileInfoMapper.getByFileId(fileId,user.getId());
        requirePermission(userFileDTO,permission);
        return userFileDTO;
    }

    /**
     * 校验 requireFileByIdActiveOrRecycle 相关逻辑。
     *
     * @param fileId 文件 ID
     * @param permission 方法入参
     * @return 处理结果
     */
    private UserFileDTO requireFileByIdActiveOrRecycle(Long fileId, FilePermission permission) {
        User user = currentUser();
        UserFileDTO userFileDTO = Admin(user) ?
                fileInfoMapper.getByFileIdAnyActiveOrRecycle(fileId) :
                fileInfoMapper.getByFileIdActiveOrRecycle(fileId,user.getId());
        requirePermission(userFileDTO,permission);
        return userFileDTO;
    }

    /**
     * 校验所有权并获取文件（通过 fileUuid 获取文件）
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param permission 方法入参
     * @return 处理结果
     */
    private UserFileDTO requireFileByUuid(String fileUuid, Long parentId, FilePermission permission) {
        User user = currentUser();
        Long realParentId = normalizeParentId(parentId,user.getId());
        UserFileDTO userFileDTO = Admin(user) ?
                fileInfoMapper.getByFileUuidAny(fileUuid,realParentId) :
                fileInfoMapper.getByFileUuidAndParent(fileUuid,realParentId,user.getId());
        requirePermission(userFileDTO,permission);
        return userFileDTO;
    }

    /**
     * 检查目录存在状态 & 权限
     *
     * @param directory 方法入参
     */
    private void requireWritableDirectory(UserFileDTO directory) {
        requirePermission(directory,FilePermission.WRITE);
        if(directory.getDir() != 1) {
            throw new BaseException("目标位置不是目录");
        }
    }

    /**
     * 列举当前目录下的文件。
     *
     * @param parentId 父级 ID
     * @param ownerId 方法入参
     * @return 列表结果
     */
    private List<UserFileDTO> listChildren(Long parentId, Long ownerId) {
        User user = currentUser();
        if(Admin(user)) {
            return fileInfoMapper.listFileByparentIdAny(parentId);
        }
        return fileInfoMapper.listFileByparentId(parentId,ownerId);
    }

    /**
     * 查询 listChildrenActiveOrRecycle 相关逻辑。
     *
     * @param parentId 父级 ID
     * @param ownerId 方法入参
     * @return 列表结果
     */
    private List<UserFileDTO> listChildrenActiveOrRecycle(Long parentId, Long ownerId) {
        User user = currentUser();
        if(Admin(user)) {
            return fileInfoMapper.listFileByparentIdAnyActiveOrRecycle(parentId);
        }
        return fileInfoMapper.listFileByparentIdActiveOrRecycle(parentId,ownerId);
    }

    /**
     * 校验 requireNoRestoreNameConflict 相关逻辑。
     *
     * @param userFileDTO 方法入参
     */
    private void requireNoRestoreNameConflict(UserFileDTO userFileDTO) {
        List<UserFileDTO> siblings = listChildren(userFileDTO.getParentId(),userFileDTO.getUserId());
        for(UserFileDTO sibling : siblings) {
            boolean sameNode = sibling.getId().equals(userFileDTO.getId());
            boolean sameName = sibling.getFileName().equals(userFileDTO.getFileName());
            boolean sameType = sibling.getDir() == userFileDTO.getDir();
            if(!sameNode && sameName && sameType) {
                throw new BaseException("恢复失败，目标目录存在同名文件或目录");
            }
        }
    }

    /**
     * 文件获取与创建
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private File File_Info(String fileUuid, Long parentId, Long userId) {
        UserFileDTO userFileDTO = parentId == null ?
                fileInfoMapper.getByFileUuid(fileUuid,userId) :
                fileInfoMapper.getByFileUuidAndParent(fileUuid,parentId,userId);
        if(userFileDTO == null) {
            throw new BaseException("文件不存在或没有读取权限");
        }
        File file = fileInfoMapper.getFileInfo(fileUuid,userId);
        if(file == null) {
            file = File.builder()
                    .fileUuid(fileUuid)
                    .dir(userFileDTO.getDir() == 1)
                    .name(userFileDTO.getFileName())
                    .type(userFileDTO.getDir() == 1 ? "dir" : null)
                    .status(userFileDTO.getStatus())
                    .createTime(userFileDTO.getCreatetime())
                    .updateTime(userFileDTO.getUpdatetime())
                    .build();
        }
        file.setUpdateTime(userFileDTO.getUpdatetime());
        file.setPath(userFileDTO.getPath());
        file.setName(userFileDTO.getFileName());
        file.setParentId(userFileDTO.getParentId());
        file.setUserId(userId);
        return file;
    }

    /**
     * 查询 getFileType 相关逻辑。
     * 获取文件类型
     *
     * @param name 名称
     * @return 处理结果
     */
    private String getFileType(String name) {
        if(name == null) {
            return ".txt";
        }
        int dotIndex = name.lastIndexOf(".");
        if(dotIndex < 0 || dotIndex == name.length() - 1) {
            return ".txt";
        }
        return name.substring(dotIndex);
    }


    /**
     * 查询 getFileType 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private String getFileType(String fileUuid,Long userId) {
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(userFileDTO == null) {
            log.warn("getFileType 正在读取一个不属于用户的文件");
            throw new RuntimeException("获取文件类型失败");
        }
        File file = fileInfoMapper.getFileByFileUuid(fileUuid,userId);
        return file.getType();
    }


    /**
     * 执行 FileType 函数的业务处理。
     *
     * @param fileName 文件名
     * @return 处理结果
     */
    private String FileType(String fileName) {
        return getFileType(fileName);
    }

    /**
     * 文件名合规检查
     *
     * @param fileName 原文件名
     * @return 当文件名合规时返回
     */

    private String requireSafeFileName(String fileName) {
        if(fileName == null) {
            throw new BaseException("文件名不能为空");
        }
        String normalized = fileName.trim(); // 删除空格，返回新字符串
        if(normalized.isEmpty() || normalized.length() > 255) {
            throw new BaseException("文件名不能为空且不能超过 255 个字符");
        }
        if(normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new BaseException("文件名包含非法路径字符");
        }
        return normalized;
    }

    /**
     * 文件上传限制检查：空文件、大文件、违规名文件
     *
     * @param uploadFile 上传的文件
     */

    private void validateUploadFile(MultipartFile uploadFile) {
        if(uploadFile == null || uploadFile.isEmpty()) {
            throw new BaseException("上传文件不能为空");
        }
        if(uploadFile.getSize() > maxFileSize) {
            throw new BaseException("文件大小超过限制");
        }
        requireSafeFileName(uploadFile.getOriginalFilename());
    }

    /**
     * 检查命名冲突
     *
     * @param fileName 文件名
     * @param dir 文件：0 / 文件夹：1
     * @param parentId 父目录
     * @param userId 用户 ID
     * @param ignoreId 忽略检查的文件 ID，重命名时使用
     */
    private void requireNoNameConflict(String fileName, int dir, Long parentId, Long userId, Long ignoreId) {
        List<UserFileDTO> siblings = listChildren(parentId,userId);
        for(UserFileDTO sibling : siblings) {
            if(ignoreId != null && ignoreId.equals(sibling.getId())) {
                continue;
            }
            if(fileName.equals(sibling.getFileName()) && sibling.getDir() == dir) {
                throw new BaseException("目标目录已存在同名文件或目录");
            }
        }
    }

    /**
     * 查询 getPath 相关逻辑。
     * 获取文件路径
     *
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private String getPath(Long fileId,Long userId) {
        UserFileDTO userFileDTO = fileInfoMapper.getByFileId(fileId,userId);
        if(userFileDTO == null) {
            log.warn("文件不存在，获取路径失败: {},{}",fileId,userId);
            throw new RuntimeException("获取路径失败");
        }
        if(userFileDTO.getParentId() == 0L || userFileDTO.getParentId() == fileId) {
            return "/";
        }
        UserFileDTO userFileDTO1 = fileInfoMapper.getByFileId(userFileDTO.getParentId(),userId);
        return userFileDTO1.getPath() + userFileDTO.getFileName() + "/";
    }


    /**
     * 规范化 normalizeParentId 相关逻辑。
     * 获取正确的父目录，以及新用户创建时为其定向一个正确的父目录（防止自环）
     *
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private Long normalizeParentId(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            UserFileDTO root = fileInfoMapper.getRootDirByUserId(userId);
            if (root != null) {
                return root.getId();
            }

            LocalDateTime now = LocalDateTime.now();
            root = UserFileDTO.builder()
                    .fileUuid(UuidUtil.randomUuid())
                    .Dir(1)
                    .userId(userId)
                    .parentId(0L)
                    .fileName("/")
                    .path("/")
                    .status(1)
                    .createtime(now)
                    .updatetime(now)
                    .build();
            fileInfoMapper.insertFile_User(root);
            return root.getId();
        }

        User user = loginMapper.getById(userId);
        boolean parentExists = Admin(user) ? fileInfoMapper.ParentIdExistAny(parentId) : fileInfoMapper.ParentIdExist(parentId,userId);
        if (!parentExists) {
            log.info("尝试访问一个不存在的目录");
            throw new RuntimeException("目录不存在");
        }
        return parentId;
    }

    /**
     * 查询 getRootId 相关逻辑。
     * 获取当前用户根目录
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    public Long getRootId(Long userId) {
        return normalizeParentId(null,userId);
    }

    /**
     * 上传 upload 相关逻辑。
     *
     * @param uploadFile 上传文件
     * @param parentId 父级 ID
     * @return 处理结果
     */
    public FileVO upload(MultipartFile uploadFile,Long parentId) {
        Long userId = BaseContext.getCurrentId();
        validateUploadFile(uploadFile);
        String originalFilename = requireSafeFileName(uploadFile.getOriginalFilename());
        if (uploadFile.isEmpty()) {
            log.warn("用户尝试上传空文件");
            throw new RuntimeException("上传文件不能为空");
        }
        parentId = normalizeParentId(parentId, userId);
        UserFileDTO parent = requireFileById(parentId,FilePermission.WRITE);
        requireWritableDirectory(parent);
        Long ownerId = parent.getUserId();
        requireNoNameConflict(originalFilename,0,parentId,ownerId,null);


        String md5;
        String sha1;
        String hash;
        try {
            md5 = Md5Util.md5(uploadFile.getInputStream());
            sha1 = HashUtil.sha1(uploadFile.getInputStream());
            hash = HashUtil.sha256(uploadFile.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("文件解析失败", e);
        }

        log.info("文件名: {}, MD5: {}, hash: {}", uploadFile.getOriginalFilename(), md5, hash);

        // 查询是否已有相同 hash 的真实文件。
        File existingFile = fileInfoMapper.getFileByHash(hash);
        // 不存在同 hash 文件，需要上传 MinIO 并写入 file_info。
        if(existingFile == null) {
            String fileUuid = UuidUtil.randomUuid();
            File exist = fileInfoMapper.getFileByHash(hash);
            File file = File.builder()
                    .name(originalFilename)
                    .fileUuid(fileUuid)
                    .dir(false)
                    .type(getFileType(originalFilename))
                    .size(uploadFile.getSize())
                    .hash(hash)
                    .md5(md5)
                    .sha1(sha1)
                    .status(1)
                    .count(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            UserFileDTO file_user = UserFileDTO.builder()
                    .userId(ownerId)
                    .parentId(parentId)
                    .fileUuid(fileUuid)
                    .fileName(originalFilename)
                    .status(1)
                    .Dir(0)
                    .path(null)
                    .createtime(file.getCreateTime())
                    .updatetime(file.getUpdateTime()).build();
            log.info("文件{}的哈希值校验未通过，重新上传",uploadFile.getName());

            try {
                minioclientUtil.putObject(uploadFile,file.getFileUuid());
            } catch (Exception e) {
                log.error("uuid对应文件{}上传失败，原因：{}",file.getFileUuid(),e.getMessage());
                throw new RuntimeException(e);
            }
            fileInfoMapper.insertFileInfo(file);

            // 回填 user_file
            fileInfoMapper.insertFile_User(file_user);
            file_user.setPath(getPath(file_user.getId(),ownerId));
            fileInfoMapper.updatePath(file_user.getId(), file_user.getFileUuid(),file_user.getPath(),ownerId);
            log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());

            return toFileVO(file);
        }
        else {
            UserFileDTO same = fileInfoMapper.getByFileUuidAndParent(existingFile.getFileUuid(),parentId, ownerId);
            if(same != null) {
                log.warn("同目录下已有相同文件");
                throw new RuntimeException("已存在相同文件");
            }
            // 复用已有真实文件，只新增用户文件树节点。
            File file = new File();
            BeanUtils.copyProperties(existingFile,file);
            UserFileDTO file_user = UserFileDTO.builder()
                    .userId(ownerId)
                    .parentId(parentId)
                    .fileUuid(file.getFileUuid())
                    .fileName(originalFilename)
                    .status(1)
                    .Dir(0)
                    .path(null)
                    .createtime(LocalDateTime.now())
                    .updatetime(LocalDateTime.now()).build();
            // file_info 璁℃暟
            if(fileInfoMapper.updateFileCount(file.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }

            // 鎻掑叆 user_file
            fileInfoMapper.insertFile_User(file_user);
            file_user.setPath(getPath(file_user.getId(),ownerId));
            fileInfoMapper.updatePath(file_user.getId(), file_user.getFileUuid(),file_user.getPath(),ownerId);
            log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());
            return toFileVO(file);
        }
    }

    /**
     * 下载 downloadFile 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param response 响应对象
     */
    public void downloadFile(String fileUuid, Long parentId, HttpServletResponse response) {
        UserFileDTO userFileDTO = requireFileByUuid(fileUuid,parentId,FilePermission.READ);
        Long ownerId = userFileDTO.getUserId();

        //TODO：未来需要支持打包下载
        if(userFileDTO.getDir() == 1) {
            throw new BaseException("目录不支持下载");
        }
        if(!userFileAvailable(userFileDTO)) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("文件不可用");
        }

        File files = fileInfoMapper.getFileByFileUuid(fileUuid,ownerId);
        FileDTO fileDTO = new FileDTO();
        BeanUtils.copyProperties(files,fileDTO);
        fileDTO.setName(userFileDTO.getFileName());

        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            log.error("文件{}下载失败: {}",fileUuid,e.getMessage());
            throw new RuntimeException("分享文件下载失败",e);
        }
        log.info("uuid{}文件下载完成",fileUuid);
    }

    /**
     * 预览 previewFile 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    public FilePreviewVO previewFile(String fileUuid, Long parentId) {
        UserFileDTO userFileDTO = requirePreviewableFile(fileUuid,parentId);
        File file = fileInfoMapper.getFileByFileUuid(fileUuid,userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("文件元数据不存在");
        }

        String contentType = resolveContentType(userFileDTO.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,userFileDTO.getFileName());
        FilePreviewVO filePreviewVO = new FilePreviewVO();
        filePreviewVO.setFileUuid(fileUuid);
        filePreviewVO.setName(userFileDTO.getFileName());
        filePreviewVO.setPreviewType(previewType);
        filePreviewVO.setContentType(contentType);
        filePreviewVO.setSize(file.getSize());

        if("text".equals(previewType)) {
            filePreviewVO.setTextContent(readTextPreview(file));
            return filePreviewVO;
        }
        if("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType) || "audio".equals(previewType)) {
            filePreviewVO.setPreviewUrl("/api/file/preview/" + fileUuid + "/stream?parentId=" + parentId);
            return filePreviewVO;
        }
        throw new BaseException("当前文件类型不支持预览");
    }

    /**
     * 预览 previewFileStream 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param response 响应对象
     */
    public void previewFileStream(String fileUuid, Long parentId, HttpServletResponse response) {
        UserFileDTO userFileDTO = requirePreviewableFile(fileUuid,parentId);
        File file = fileInfoMapper.getFileByFileUuid(fileUuid,userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("文件元数据不存在");
        }

        String contentType = resolveContentType(userFileDTO.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,userFileDTO.getFileName());
        if("text".equals(previewType)) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        if(!"image".equals(previewType) && !"pdf".equals(previewType) && !"video".equals(previewType) && !"audio".equals(previewType) && !"text".equals(previewType)) {
            throw new BaseException("当前文件类型不支持预览");
        }
        try {
            minioclientUtil.previewObject(fileUuid,userFileDTO.getFileName(),contentType,response);
        } catch (Exception e) {
            log.error("文件{}预览失败: {}",fileUuid,e.getMessage());
            throw new RuntimeException("文件预览失败",e);
        }
    }

    /**
     * 校验 requirePreviewableFile 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    private UserFileDTO requirePreviewableFile(String fileUuid, Long parentId) {
        UserFileDTO userFileDTO = requireFileByUuid(fileUuid,parentId,FilePermission.READ);
        if(userFileDTO.getDir() == 1) {
            throw new BaseException("目录不支持预览");
        }
        if(!userFileAvailable(userFileDTO)) {
            throw new BaseException("文件不可用");
        }
        return userFileDTO;
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
            log.error("文本文件{}读取失败: {}",file.getFileUuid(),e.getMessage());
            throw new RuntimeException("文本预览失败",e);
        }
    }

    /**
     * 解析 resolvePreviewType 相关逻辑。
     *
     * @param contentType 方法入参
     * @param fileName 文件名
     * @return 处理结果
     */
    private String resolvePreviewType(String contentType, String fileName) {
        if(contentType.startsWith("image/")) {
            return "image";
        }
        if("application/pdf".equals(contentType)) {
            return "pdf";
        }
        if(contentType.startsWith("video/")) {
            return "video";
        }
        if(contentType.startsWith("audio/")) {
            return "audio";
        }
        if(contentType.startsWith("text/") || hasExtension(fileName,".md",".json",".xml",".csv",".log",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) {
            return "text";
        }
        return "unsupported";
    }

    /**
     * 解析 resolveContentType 相关逻辑。
     * 推断文件类型，指导浏览器正确预览/打开
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
        // String... extensions 是 Java 的可变参数写法，表示这个方法可以接收任意数量的 String 参数。
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
     * 执行 shareFile 函数的业务处理。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    public String shareFile(String fileUuid, Long parentId) {
        UserFileDTO userFileDTO = requireFileByUuid(fileUuid,parentId,FilePermission.READ);
        if(!userFileAvailable(userFileDTO)) {
            throw new BaseException("文件不可用，无法分享");
        }

        FileShare exists = fileShareMapper.getActiveByUserFileId(userFileDTO.getId(),userFileDTO.getUserId());
        if(exists != null) {
            return "/api/share/" + exists.getShareCode();
        }

        LocalDateTime now = LocalDateTime.now();
        FileShare fileShare = new FileShare();
        fileShare.setShareCode(generateUniqueShareCode());
        fileShare.setUserFileId(userFileDTO.getId());
        fileShare.setFileUuid(userFileDTO.getFileUuid());
        fileShare.setOwnerId(userFileDTO.getUserId());
        fileShare.setStatus(StatusConstant.ENABLE);
        fileShare.setCreateTime(now);
        fileShare.setUpdateTime(now);
        int rows = fileShareMapper.insert(fileShare);
        if(rows == 0) {
            throw new BaseException("创建分享链接失败");
        }
        return "/api/share/" + fileShare.getShareCode();
    }

    /**
     * 执行 generateUniqueShareCode 函数的业务处理。
     * @return 处理结果
     */
    private String generateUniqueShareCode() {
        for(int i = 0; i < 10; i++) {
            String shareCode = ShareCodeUtil.generate();
            if(!fileShareMapper.existsByShareCode(shareCode)) {
                return shareCode;
            }
        }
        throw new BaseException("生成分享码失败，请重试");
    }

    /**
     * 查询 getSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @return 处理结果
     */
    public ShareFileVO getSharedFile(String shareCode) {
        UserFileDTO userFileDTO = getValidSharedUserFile(shareCode);
        return buildShareFileVO(shareCode,userFileDTO,true);
    }

    /**
     * 构建 buildShareFileVO 相关逻辑。
     *
     * @param shareCode 分享码
     * @param userFileDTO 方法入参
     * @param includeChildren 方法入参
     * @return 处理结果
     */
    private ShareFileVO buildShareFileVO(String shareCode, UserFileDTO userFileDTO, boolean includeChildren) {
        ShareFileVO shareFileVO = new ShareFileVO();
        shareFileVO.setFileId(userFileDTO.getId());
        shareFileVO.setName(userFileDTO.getFileName());
        shareFileVO.setDir(userFileDTO.getDir() == 1);
        if(userFileDTO.getDir() == 1) {
            if(includeChildren) {
                List<ShareFileVO> children = new ArrayList<>();
                List<UserFileDTO> childFiles = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userFileDTO.getUserId());
                childFiles.forEach(child -> children.add(buildShareFileVO(shareCode,child,true)));
                shareFileVO.setChildren(children);
            }
            return shareFileVO;
        }

        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("分享文件不存在或已失效");
        }
        String contentType = resolveContentType(userFileDTO.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,userFileDTO.getFileName());
        shareFileVO.setPreviewType(previewType);
        shareFileVO.setContentType(contentType);
        shareFileVO.setSize(file.getSize());
        shareFileVO.setDownloadUrl("/api/share/" + shareCode + "?download=true&fileId=" + userFileDTO.getId());
        if("text".equals(previewType)) {
            shareFileVO.setPreviewUrl("/api/share/" + shareCode + "?preview=true&fileId=" + userFileDTO.getId());
            shareFileVO.setTextContent(readTextPreview(file));
        } else if("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType) || "audio".equals(previewType)) {
            shareFileVO.setPreviewUrl("/api/share/" + shareCode + "?stream=true&fileId=" + userFileDTO.getId());
        }
        return shareFileVO;
    }

    /**
     * 下载 downloadSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    public void downloadSharedFile(String shareCode, Long fileId, HttpServletResponse response) {
        UserFileDTO userFileDTO = getValidSharedTargetFile(shareCode,fileId);
        if(userFileDTO.getDir() == 1) {
            throw new BaseException("目录不支持分享下载");
        }

        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("分享文件不存在或已失效");
        }

        FileDTO fileDTO = new FileDTO();
        BeanUtils.copyProperties(file,fileDTO);
        fileDTO.setName(userFileDTO.getFileName());
        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            log.error("分享文件{}下载失败: {}",shareCode,e.getMessage());
            throw new RuntimeException("分享文件下载失败",e);
        }
    }

    /**
     * 预览 previewSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @return 处理结果
     */
    public FilePreviewVO previewSharedFile(String shareCode, Long fileId) {
        UserFileDTO userFileDTO = requirePreviewableSharedFile(shareCode,fileId);
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("分享文件不存在或已失效");
        }

        String contentType = resolveContentType(userFileDTO.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,userFileDTO.getFileName());
        FilePreviewVO filePreviewVO = new FilePreviewVO();
        filePreviewVO.setFileUuid(userFileDTO.getFileUuid());
        filePreviewVO.setName(userFileDTO.getFileName());
        filePreviewVO.setPreviewType(previewType);
        filePreviewVO.setContentType(contentType);
        filePreviewVO.setSize(file.getSize());

        if("text".equals(previewType)) {
            filePreviewVO.setTextContent(readTextPreview(file));
            return filePreviewVO;
        }
        if("image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType) || "audio".equals(previewType)) {
            filePreviewVO.setPreviewUrl("/api/share/" + shareCode + "?stream=true&fileId=" + userFileDTO.getId());
            return filePreviewVO;
        }
        throw new BaseException("当前文件类型不支持预览");
    }

    /**
     * 预览 previewSharedFileStream 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    public void previewSharedFileStream(String shareCode, Long fileId, HttpServletResponse response) {
        UserFileDTO userFileDTO = requirePreviewableSharedFile(shareCode,fileId);
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("分享文件不存在或已失效");
        }

        String contentType = resolveContentType(userFileDTO.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,userFileDTO.getFileName());
        if("text".equals(previewType)) {
            contentType = contentType + ";charset=UTF-8";
        }
        if(!"image".equals(previewType) && !"pdf".equals(previewType) && !"video".equals(previewType) && !"audio".equals(previewType) && !"text".equals(previewType)) {
            throw new BaseException("当前文件类型不支持预览");
        }

        try {
            minioclientUtil.previewObject(userFileDTO.getFileUuid(),userFileDTO.getFileName(),contentType,response);
        } catch (Exception e) {
            log.error("分享文件{}预览失败: {}",shareCode,e.getMessage());
            throw new RuntimeException("分享文件预览失败",e);
        }
    }

    /**
     * 校验 requirePreviewableSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @return 处理结果
     */
    private UserFileDTO requirePreviewableSharedFile(String shareCode, Long fileId) {
        UserFileDTO userFileDTO = getValidSharedTargetFile(shareCode,fileId);
        if(userFileDTO.getDir() == 1) {
            throw new BaseException("目录不支持预览");
        }
        return userFileDTO;
    }

    /**
     * 查询 getValidSharedUserFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @return 处理结果
     */
    private UserFileDTO getValidSharedUserFile(String shareCode) {
        FileShare fileShare = fileShareMapper.getActiveByShareCode(shareCode);
        if(fileShare == null) {
            throw new BaseException("分享链接不存在或已失效");
        }

        UserFileDTO userFileDTO = fileInfoMapper.getByFileIdAny(fileShare.getUserFileId());
        if(userFileDTO == null || userFileDTO.getStatus() != StatusConstant.ENABLE) {
            throw new BaseException("分享文件不存在或已失效");
        }
        if(!userFileAvailable(userFileDTO)) {
            throw new BaseException("分享文件不可用");
        }
        return userFileDTO;
    }

    /**
     * 查询 getValidSharedTargetFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @return 处理结果
     */
    private UserFileDTO getValidSharedTargetFile(String shareCode, Long fileId) {
        UserFileDTO sharedRoot = getValidSharedUserFile(shareCode);
        if(fileId == null || fileId.equals(sharedRoot.getId())) {
            return sharedRoot;
        }
        if(sharedRoot.getDir() != 1) {
            throw new BaseException("分享链接无权访问该文件");
        }

        UserFileDTO target = fileInfoMapper.getByFileId(fileId,sharedRoot.getUserId());
        if(target == null || !isSharedDescendant(sharedRoot.getId(),target,sharedRoot.getUserId())) {
            throw new BaseException("分享链接无权访问该文件");
        }
        if(!userFileAvailable(target)) {
            throw new BaseException("分享文件不可用");
        }
        return target;
    }

    /**
     * 执行 isSharedDescendant 函数的业务处理。
     *
     * @param sharedRootId 方法入参
     * @param target 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    private boolean isSharedDescendant(Long sharedRootId, UserFileDTO target, Long userId) {
        Long currentId = target.getId();
        while(currentId != null && currentId != 0L) {
            if(currentId.equals(sharedRootId)) {
                return true;
            }
            UserFileDTO current = fileInfoMapper.getByFileId(currentId,userId);
            if(current == null || current.getParentId() == null || current.getParentId().equals(currentId)) {
                return false;
            }
            currentId = current.getParentId();
        }
        return false;
    }

    /**
     * 查询 listFiles 相关逻辑。
     *
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<FileVO> listFiles(Long parentId,Long userId) {
        parentId = normalizeParentId(parentId, userId);
        UserFileDTO parent = requireFileById(parentId,FilePermission.READ);
        if(parent.getDir() != 1) {
            throw new BaseException("目标位置不是目录");
        }
        final Long temp = parentId;
        List<UserFileDTO> list = listChildren(parentId,parent.getUserId());
        List<FileVO> files = new ArrayList<>();
        list.forEach(fileiter -> {
            files.add( toFileVO( File_Info(fileiter.getFileUuid(),temp,fileiter.getUserId()) ));
        });
        return files;
    }

    /**
     * 执行 bucketExists 函数的业务处理。
     * @return 处理结果
     */
    public boolean bucketExists() {
        try {
            minioclientUtil.bucketExists(NameConstant.DEFAULT_BUCKETNAME);
        } catch (Exception e) {
            log.error("娌℃湁杩欎釜妗讹細{}",NameConstant.DEFAULT_BUCKETNAME);
            throw new RuntimeException(e.getMessage());
        }
        return true;
    }

    /**
     * 重命名 renameFile 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param newName 新名称
     * @return 处理结果
     */
    public FileVO renameFile(String fileUuid, Long parentId, String newName) {
        UserFileDTO userFileDTO = requireFileByUuid(fileUuid,parentId,FilePermission.MODIFY);
        newName = requireSafeFileName(newName);
        if(newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }
        if(!userFileAvailable(userFileDTO)) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("重命名失败");
        }

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getParentId(),userFileDTO.getUserId());
        for(UserFileDTO fileiter:files) {
            if(fileiter.getFileName().equals(newName) && fileiter.getDir() == userFileDTO.getDir() /*&& fileiter.getType.equals(file.getType())*/) {
                log.warn("同目录下存在同名文件");
                throw new RuntimeException("存在同名文件，重命名失败");
            }
        }

        int rows = fileInfoMapper.updateNameById(userFileDTO.getId(),newName,LocalDateTime.now());
        if(rows == 0) {throw new RuntimeException("重命名失败");}

        return toFileVO(File_Info(fileUuid,parentId,userFileDTO.getUserId()));
        //return toFileVO(userFileDTO);
    }

    /**
     * 删除 deleteOSS 相关逻辑。
     *
     * @param userFileDTO 方法入参
     */
    public void deleteOSS(UserFileDTO userFileDTO) {
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("文件元数据不存在");
        }
        try {
            minioclientUtil.removeObject(file);
        } catch (Exception e) {
            log.warn("删除文件{}失败",file.getFileUuid());
            throw new RuntimeException("删除失败，原因: " + e.getMessage());
        }
        fileInfoMapper.delete_fileinfo_ByfileUuid(userFileDTO.getFileUuid());
        return ;
    }

    /**
     * 执行 softDeleteTree 函数的业务处理。
     *
     * @param userFileDTO 方法入参
     */
    private void softDeleteTree(UserFileDTO userFileDTO) {
        requirePermission(userFileDTO,FilePermission.DELETE);
        if(userFileDTO.getStatus() == StatusConstant.RECYCLE) {
            return;
        }
        if(userFileDTO.getStatus() != StatusConstant.ENABLE) {
            throw new BaseException("文件状态不可删除");
        }
        fileShareMapper.disableByUserFileId(userFileDTO.getId(),userFileDTO.getUserId());
        List<UserFileDTO> children = listChildren(userFileDTO.getId(),userFileDTO.getUserId());
        children.forEach(this::softDeleteTree);
        int rows = fileInfoMapper.updateStatusById(userFileDTO.getId(),StatusConstant.RECYCLE,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件移入回收站失败");
        }
    }

    /**
     * 删除 deleteFiles 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    @Transactional
    public boolean deleteFiles(String fileUuid,Long parentId) {
        UserFileDTO userFileDTO = requireFileByUuid(fileUuid,parentId,FilePermission.DELETE);
        softDeleteTree(userFileDTO);
        return StatusConstant.SUCCESS;
    }

    /**
     * 删除 deletelot 相关逻辑。
     *
     * @param deleteList 方法入参
     * @return 处理结果
     */
    @Transactional
    public Boolean deletelot(List<UserFileDTO> deleteList) {
        deleteList.forEach(file -> {
            UserFileDTO target = requireFileById(file.getId(),FilePermission.DELETE);
            softDeleteTree(target);
        });
        return StatusConstant.SUCCESS;
    }

    /**
     * 查询 listRecycleFiles 相关逻辑。
     * @return 列表结果
     */
    public List<FileVO> listRecycleFiles() {
        User user = currentUser();
        List<UserFileDTO> recycleFiles = Admin(user) ?
                fileInfoMapper.listRecycleRootAny() :
                fileInfoMapper.listRecycleRootByUserId(user.getId());
        List<FileVO> files = new ArrayList<>();
        recycleFiles.forEach(file -> files.add(toFileVO(file)));
        return files;
    }

    /**
     * 恢复 restoreRecycleFile 相关逻辑。
     *
     * @param fileId 文件 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean restoreRecycleFile(Long fileId) {
        UserFileDTO userFileDTO = requireFileByIdActiveOrRecycle(fileId,FilePermission.MODIFY);
        if(userFileDTO.getStatus() != StatusConstant.RECYCLE) {
            throw new BaseException("文件不在回收站中");
        }
        UserFileDTO parent = fileInfoMapper.getByFileIdAnyActiveOrRecycle(userFileDTO.getParentId());
        if(parent != null && parent.getStatus() == StatusConstant.RECYCLE) {
            throw new BaseException("父目录仍在回收站中，请先恢复父目录");
        }
        if(userFileDTO.getParentId() != null && userFileDTO.getParentId() != 0L && parent == null) {
            throw new BaseException("父目录不存在，无法恢复文件");
        }
        requireNoRestoreNameConflict(userFileDTO);
        restoreTree(userFileDTO);
        return StatusConstant.SUCCESS;
    }

    /**
     * 恢复 restoreTree 相关逻辑。
     *
     * @param userFileDTO 方法入参
     */
    private void restoreTree(UserFileDTO userFileDTO) {
        requirePermission(userFileDTO,FilePermission.MODIFY);
        if(userFileDTO.getStatus() == StatusConstant.ENABLE) {
            return;
        }
        if(userFileDTO.getStatus() != StatusConstant.RECYCLE) {
            throw new BaseException("文件状态不可恢复");
        }
        int rows = fileInfoMapper.updateStatusById(userFileDTO.getId(),StatusConstant.ENABLE,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件恢复失败");
        }
        List<UserFileDTO> children = listChildrenActiveOrRecycle(userFileDTO.getId(),userFileDTO.getUserId());
        children.forEach(child -> {
            if(child.getStatus() == StatusConstant.RECYCLE) {
                restoreTree(child);
            }
        });
    }

    /**
     * 删除 deleteRecycleFilePermanently 相关逻辑。
     *
     * @param fileId 文件 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean deleteRecycleFilePermanently(Long fileId) {
        UserFileDTO userFileDTO = requireFileByIdActiveOrRecycle(fileId,FilePermission.DELETE);
        if(userFileDTO.getStatus() != StatusConstant.RECYCLE) {
            throw new BaseException("只能彻底删除回收站中的文件");
        }
        hardDeleteTree(userFileDTO);
        return StatusConstant.SUCCESS;
    }

    /**
     * 执行 hardDeleteTree 函数的业务处理。
     *
     * @param userFileDTO 方法入参
     */
    private void hardDeleteTree(UserFileDTO userFileDTO) {
        requirePermission(userFileDTO,FilePermission.DELETE);
        if(userFileDTO.getDir() == 1) {
            List<UserFileDTO> children = listChildrenActiveOrRecycle(userFileDTO.getId(),userFileDTO.getUserId());
            children.forEach(this::hardDeleteTree);
            hardDeleteUserFile(userFileDTO);
            return;
        }
        hardDeletePhysicalFileReference(userFileDTO);
    }

    /**
     * 执行 hardDeleteUserFile 函数的业务处理。
     *
     * @param userFileDTO 方法入参
     */
    private void hardDeleteUserFile(UserFileDTO userFileDTO) {
        int rows = fileInfoMapper.deleteByFileIdAny(userFileDTO.getId());
        if(rows == 0) {
            throw new BaseException("文件节点彻底删除失败");
        }
    }

    /**
     * 执行 hardDeletePhysicalFileReference 函数的业务处理。
     *
     * @param userFileDTO 方法入参
     */
    private void hardDeletePhysicalFileReference(UserFileDTO userFileDTO) {
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userFileDTO.getUserId());
        if(file == null) {
            throw new BaseException("文件元数据不存在");
        }
        hardDeleteUserFile(userFileDTO);
        if(fileInfoMapper.updateFileCount(userFileDTO.getFileUuid(),-1) == 0) {
            throw new BaseException("文件引用计数更新失败");
        }
        if(fileInfoMapper.getFileCount(userFileDTO.getFileUuid()) == 0) {
            deleteOSS(userFileDTO);
        }
    }

    /**
     * 执行 makefile 函数的业务处理。
     *
     * @param isDir 方法入参
     * @param parentId 父级 ID
     * @param name 名称
     * @param type 类型
     * @return 处理结果
     */
    public FileVO makefile(int isDir,Long parentId,String name,String type) {
        Long userId = BaseContext.getCurrentId();
        String safeName = requireSafeFileName(name);
        name = safeName;
        if(name == null || name.length() == 0) {
            log.warn("文件名不合法:{}",name);
            throw new RuntimeException("文件名不合法");
        }
        parentId = normalizeParentId(parentId, userId);
        UserFileDTO parent = requireFileById(parentId,FilePermission.WRITE);
        requireWritableDirectory(parent);
        Long ownerId = parent.getUserId();

        List<UserFileDTO> files = listChildren(parentId,ownerId);
        files.forEach(fileiter -> {
            if(fileiter.getFileName().equals(safeName) && fileiter.getDir() == isDir) {
                log.warn("同目录下有重名文件");
                throw new RuntimeException("存在同名文件,请重试");
            }
        });

        File existsFile = fileInfoMapper.findFileByName(name,ownerId,parentId);
        if(existsFile != null) {
            log.warn("文件名已存在");
            throw new RuntimeException("文件名已存在！");
        }

        if(isDir == 0) {
            File file = File.builder()
                    .fileUuid(UuidUtil.randomUuid())
                    .parentId(parentId)
                    .userId(ownerId)
                    .size(0L)
                    .name(name)
                    .type(type)
                    .status(StatusConstant.ENABLE)
                    .count(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();

            UserFileDTO userFileDTO = UserFileDTO.builder()
                    .fileUuid(file.getFileUuid())
                    .userId(ownerId)
                    .fileName(name)
                    .status(StatusConstant.ENABLE)
                    .parentId(parentId)
                    .path(getPath(parentId,ownerId) + file.getName())
                    .createtime(file.getCreateTime())
                    .updatetime(file.getUpdateTime())
                    .build();

            file.setDir(false);
            userFileDTO.setDir(0);
            try {
                minioclientUtil.putEmptyObject(file.getFileUuid());
            } catch (Exception e) {
                log.warn("创建空文件对象失败：{}",file.getFileUuid(),e);
                throw new RuntimeException("新建文件失败");
            }
            int rows = fileInfoMapper.insertFileInfo(file);
            if(rows == 0) {
                log.warn("新建文件失败");
                throw new RuntimeException("新建文件失败！");
            }
            rows = fileInfoMapper.insertFile_User(userFileDTO);
            if(rows == 0) {
                log.warn("新建文件失败");
                throw new RuntimeException("新建文件失败！");
            }
            return toFileVO(file);
        }

        String uuid = UuidUtil.randomUuid();
        File file = File.builder()
                .fileUuid(uuid)
                .parentId(parentId)
                .userId(ownerId)
                .size(0L)
                .dir(true)
                .name(name)
                .type("dir")
                .status(StatusConstant.ENABLE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        UserFileDTO userFileDTO = UserFileDTO.builder()
                .fileUuid(uuid)
                .userId(ownerId)
                .fileName(name)
                .status(StatusConstant.ENABLE)
                .Dir(1)
                .parentId(parentId)
                .createtime(LocalDateTime.now())
                .updatetime(LocalDateTime.now())
                .build();
        fileInfoMapper.insertFile_User(userFileDTO);
        fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),getPath(userFileDTO.getId(),ownerId),ownerId);
        return toFileVO(file);
    }

    /**
     * 执行 movefiles 函数的业务处理。
     *
     * @param sourceplace 方法入参
     * @param targetplace 方法入参
     * @return 处理结果
     */
    @Transactional
    public Boolean movefiles(Long sourceplace, Long targetplace) {
        UserFileDTO files = requireFileById(sourceplace,FilePermission.MODIFY);
        UserFileDTO filet = requireFileById(targetplace,FilePermission.WRITE);
        if(files.getStatus() == 0 || filet.getStatus() == 0) {
            log.warn("文件状态错误");
            throw new RuntimeException("移动失败");
        }
        if(filet.getDir() == 0) {
            log.warn("目标位置不是目录");
            throw new RuntimeException("移动失败");
        }
        if(!files.getUserId().equals(filet.getUserId())) {
            throw new BaseException("不能跨用户移动文件");
        }
        if(files.getDir() == 1 && isChildDir(files.getId(),filet.getId(),files.getUserId())) {
            throw new BaseException("不能移动目录到自身或子目录");
        }

        requireNoNameConflict(files.getFileName(),files.getDir(),filet.getId(),files.getUserId(),files.getId());

        if(files.getDir() == 0) {
            files.setParentId( normalizeParentId( filet.getId(),filet.getUserId() ) );

            int rows = fileInfoMapper.updateParent(files.getId(),filet.getId(),LocalDateTime.now());
            if(rows == 0) {
                log.warn("移动父目录更新失败");
                throw new RuntimeException("移动失败");
            }
            log.info("文件移动成功");
            files.setPath(getPath(files.getId(),files.getUserId()));
            fileInfoMapper.updatePath(files.getId(),files.getFileUuid(),files.getPath(),files.getUserId());
            return true;
        }
        else {
            files.setParentId( normalizeParentId( filet.getId(),filet.getUserId() ) );
            fileInfoMapper.updateParentWithUuid(files.getId(),files.getFileUuid(),files.getParentId(),files.getUserId());
            Queue<UserFileDTO> queue = new LinkedList<>();
            queue.offer(fileInfoMapper.getByFileUuid(files.getFileUuid(),files.getUserId()));
            while(!queue.isEmpty()) {
                UserFileDTO userFileDTO = queue.poll();
                if(userFileDTO.getDir() == 0) {
                    userFileDTO.setPath(getPath(userFileDTO.getId(),files.getUserId()));
                    fileInfoMapper.updatePath(userFileDTO.getId(), userFileDTO.getFileUuid(), userFileDTO.getPath(), files.getUserId());
                    continue;
                }
                List<UserFileDTO> list = fileInfoMapper.listFileByparentId(userFileDTO.getId(),files.getUserId());
                list.forEach(iter -> {
                    queue.offer(iter);
                });
                userFileDTO.setPath(getPath(userFileDTO.getId(),files.getUserId()));
                fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),userFileDTO.getPath(),files.getUserId());
            }
        }
        return true;
    }

    /**
     * 复制 copyfiles 相关逻辑。
     *
     * @param sourceplace 方法入参
     * @param targetplace 方法入参
     * @return 处理结果
     */
    @Transactional
    public Boolean copyfiles(Long sourceplace, Long targetplace) {
        UserFileDTO files = requireFileById(sourceplace,FilePermission.READ);
        UserFileDTO filet = requireFileById(targetplace,FilePermission.WRITE);
        if(files.getStatus() == 0 || filet.getStatus() == 0) {
            log.warn("文件状态错误");
            throw new RuntimeException("复制失败");
        }
        if(filet.getDir() == 0) {
            log.warn("目标位置不是目录");
            throw new RuntimeException("复制失败");
        }
        if(!files.getUserId().equals(filet.getUserId())) {
            throw new BaseException("不能跨用户复制文件");
        }

        checkCopyName(files,filet.getId(),files.getUserId());
        if(files.getDir() == 1 && isChildDir(files.getId(),filet.getId(),files.getUserId())) {
            log.warn("不能复制目录到自身或子目录");
            throw new RuntimeException("复制失败");
        }

        copyFileTree(files,filet.getId(),files.getUserId());
        return true;
    }

    /**
     * 检查 checkCopyName 相关逻辑。
     *
     * @param source 方法入参
     * @param targetParentId 方法入参
     * @param userId 用户 ID
     */
    private void checkCopyName(UserFileDTO source, Long targetParentId, Long userId) {
        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(targetParentId,userId);
        files.forEach(fileiter -> {
            if(fileiter.getFileName().equals(source.getFileName()) && fileiter.getDir() == source.getDir()) {
                log.warn("目标目录存在同名文件或目录");
                throw new RuntimeException("澶嶅埗澶辫触!");
            }
        });
    }

    /**
     * 执行 isChildDir 函数的业务处理。
     *
     * @param sourceId 方法入参
     * @param targetId 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    private boolean isChildDir(Long sourceId, Long targetId, Long userId) {
        Long currentId = targetId;
        while(currentId != null && currentId != 0L) {
            if(currentId.equals(sourceId)) {
                return true;
            }
            UserFileDTO current = fileInfoMapper.getByFileId(currentId,userId);
            if(current == null || current.getParentId() == null || current.getParentId().equals(currentId)) {
                return false;
            }
            currentId = current.getParentId();
        }
        return false;
    }

    /**
     * 复制 copyFileTree 相关逻辑。
     *
     * @param source 方法入参
     * @param targetParentId 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    private UserFileDTO copyFileTree(UserFileDTO source, Long targetParentId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        UserFileDTO copied = UserFileDTO.builder()
                .fileUuid(source.getDir() == 1 ? UuidUtil.randomUuid() : source.getFileUuid())
                .userId(userId)
                .fileName(source.getFileName())
                .status(1)
                .Dir(source.getDir())
                .parentId(targetParentId)
                .path(null)
                .createtime(now)
                .updatetime(now)
                .build();

        int rows = fileInfoMapper.insertFile_User(copied);
        if(rows == 0) {
            log.warn("复制文件节点失败");
            throw new RuntimeException("复制失败");
        }
        copied.setPath(getPath(copied.getId(),userId));
        fileInfoMapper.updatePath(copied.getId(),copied.getFileUuid(),copied.getPath(),userId);

        if(source.getDir() == 0) {
            if(fileInfoMapper.updateFileCount(source.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            log.info("文件复制成功");
            return copied;
        }

        List<UserFileDTO> children = fileInfoMapper.listFileByparentId(source.getId(),userId);
        children.forEach(child -> {
            checkCopyName(child,copied.getId(),userId);
            copyFileTree(child,copied.getId(),userId);
        });
        log.info("目录复制成功");
        return copied;
    }
}
