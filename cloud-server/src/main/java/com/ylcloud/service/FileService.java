package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.constant.NameConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.File;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.UuidUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@Service
@Slf4j
public class FileService {

    @Autowired
    private FileInfoMapper fileInfoMapper;
    @Autowired
    private MinioclientUtil minioclientUtil;

    private FileVO toFileVO(File file) {
        FileVO fileVO = new FileVO();
        BeanUtils.copyProperties(file,fileVO);
        return fileVO;
    }

    private FileVO toFileVO(FileDTO fileDTO) {
        FileVO fileVO = new FileVO();
        BeanUtils.copyProperties(fileDTO,fileVO);
        return fileVO;
    }

    private boolean file_Status(String fileUuid) {
        return fileInfoMapper.getFileStatus(fileUuid);
    }

    private File File_Info(String fileUuid, Long parentId, Long userId) {
        File file = fileInfoMapper.getFileInfo(fileUuid,userId);
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        file.setUpdateTime(userFileDTO.getUpdatetime());
        file.setPath(userFileDTO.getPath());
        file.setName(userFileDTO.getFileName());
        file.setParentId(userFileDTO.getParentId());
        file.setUserId(userId);
        return file;
    }

    private String getFileType(String name) {
        return name.substring(name.lastIndexOf("."));
    }

    /**
     * 从数据库中获取对应文件，返回文件类型
     * @param fileUuid
     * @param userId
     * @return
     */

    private String getFileType(String fileUuid,Long userId) {
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(userFileDTO == null) {
            log.warn("getFileType 正在调取一个不属于用户的文件");
            throw new RuntimeException("获取文件类型失败！");
        }
        File file = fileInfoMapper.getFileByFileUuid(fileUuid,userId);
        return file.getType();
    }

    /**
     * 从名字中获取文件类型（文件后缀）
     * @param fileName
     * @return
     */

    private String FileType(String fileName) {
        String suffix = fileName.substring(fileName.lastIndexOf("."));
        if(suffix == null) suffix = ".txt";
        return suffix;
    }

    /**
     * 获取文件路径,通过询问父亲的名字实现。
     * @param fileId
     * @param userId
     * @return
     */
    private String getPath(Long fileId,Long userId) {
        UserFileDTO userFileDTO = fileInfoMapper.getByFileId(fileId,userId);
        if(userFileDTO == null) {
            log.warn("文件不存在！路径错误！{},{}",fileId,userId);
            throw new RuntimeException("获取路径失败");
        }
        if(userFileDTO.getParentId() == 0L || userFileDTO.getParentId() == fileId) {
            return "/";
        }
        UserFileDTO userFileDTO1 = fileInfoMapper.getByFileId(userFileDTO.getParentId(),userId);
        return userFileDTO1.getPath() + userFileDTO.getFileName() + "/";
    }

    /**
     * parentid 校验
     * @param parentId
     * @param userId
     * @return
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

        if (!fileInfoMapper.ParentIdExist(parentId, userId)) {
            log.info("尝试访问一个不存在的目录");
            throw new RuntimeException("目录不存在");
        }
        return parentId;
    }

    /**
     *  normalizeParentId 的 public 化(理论上，只有注册时调用一次)
     * @param userId
     * @return
     */
    public Long getRootId(Long userId) {
        return normalizeParentId(null,userId);
    }

    /**
     * 上传文件
     * @param uploadFile
     * @return
     */
    public FileVO upload(MultipartFile uploadFile,Long parentId) {
        Long userId = BaseContext.getCurrentId();
        if (uploadFile == null || uploadFile.isEmpty()) {
            log.warn("用户正在尝试上传一个空文件");
            throw new RuntimeException("上传文件不能为空");
        }
        parentId = normalizeParentId(parentId, userId);


        String md5;
        String hash;
        try {
            md5 = Md5Util.md5(uploadFile.getInputStream());
            hash = HashUtil.sha256(uploadFile.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("文件摘要计算失败", e);
        }

        log.info("文件名：{}，MD5：{}，hash：{}", uploadFile.getOriginalFilename(), md5, hash);

        // 检查是否存在相同文件
        File existingFile = fileInfoMapper.getFileByHash(hash);
        // 不存在同类型文件，需要插入桶
        if(existingFile == null) {
            String fileUuid = UuidUtil.randomUuid();
            File exist = fileInfoMapper.getFileByHash(hash);
            File file = File.builder()
                    .name(uploadFile.getOriginalFilename())
                    .fileUuid(fileUuid)
                    .dir(false)
                    .type(getFileType(uploadFile.getOriginalFilename()))
                    .size(uploadFile.getSize())
                    .hash(hash)
                    .md5(md5)
                    .status(1)
                    .count(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            UserFileDTO file_user = UserFileDTO.builder()
                    .userId(userId)
                    .parentId(parentId)
                    .fileUuid(fileUuid)
                    .fileName(uploadFile.getOriginalFilename())
                    .status(1)
                    .Dir(0)
                    .path(null)
                    .createtime(file.getCreateTime())
                    .updatetime(file.getUpdateTime()).build();
            log.info("文件{}基本信息设置完成，现在开始上传",uploadFile.getName());

            try {
                minioclientUtil.putObject(uploadFile,file.getFileUuid());
            } catch (Exception e) {
                log.error("uuid编号{}文件上传失败，原因：{}",file.getFileUuid(),e.getMessage());
                throw new RuntimeException(e);
            }
            // 插入 file_info，同时完成计数
            file.setCount(1);
            fileInfoMapper.insertFileInfo(file);

            // 插入 user_file
            fileInfoMapper.insertFile_User(file_user); // 先向 user_file 插入数据，拿到 id 回填
            file_user.setPath(getPath(file_user.getId(),userId)); // 再去询问路径
            fileInfoMapper.updatePath(file_user.getId(), file_user.getFileUuid(),file_user.getPath(),userId);
            log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());

            return toFileVO(file);
        }
        else {
            // 存在同类型文件，先看看同目录下有没有同样的文件？
            UserFileDTO same = fileInfoMapper.getByFileUuid(existingFile.getFileUuid(),parentId, userId);
            if(same != null) {
                // 有，抛出错
                log.warn("同目录下有同类文件");
                throw new RuntimeException("存在同名文件！");
            }
            // 没有，沿用文件信息。
            File file = new File();
            BeanUtils.copyProperties(existingFile,file);
            UserFileDTO file_user = UserFileDTO.builder()
                    .userId(userId)
                    .parentId(parentId)
                    .fileUuid(file.getFileUuid())
                    .fileName(uploadFile.getOriginalFilename())
                    .status(1)
                    .Dir(0)
                    .path(null)
                    .createtime(LocalDateTime.now())
                    .updatetime(LocalDateTime.now()).build();
            // file_info 计数
            fileInfoMapper.updateFileCount(file.getFileUuid(),1);

            // 插入 user_file
            fileInfoMapper.insertFile_User(file_user); // 先向 user_file 插入数据，拿到 id 回填
            file_user.setPath(getPath(file_user.getId(),userId)); // 再去询问路径
            fileInfoMapper.updatePath(file_user.getId(), file_user.getFileUuid(),file_user.getPath(),userId);
            log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());
            return toFileVO(file);
        }
    }

    /**
     * 下载文件
     * 通过 http response 响应体返回文件数据
     * @param fileUuid
     * @param response
     */
    public void downloadFile(String fileUuid, Long parentId, HttpServletResponse response) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);

        if(userFileDTO == null) {
            log.warn("未找到相关文件{},{}",fileUuid,userId);
            throw new RuntimeException("文件不存在");
        }

        if(!file_Status(fileUuid) || userFileDTO.getStatus() == 0) {
            log.warn("文件{}处于锁定状态",fileUuid);
            throw new RuntimeException("文件不可用");
        }

        // TODO:需要考虑舍弃 FileDTO?
        File files = fileInfoMapper.getFileByFileUuid(fileUuid,userId);
        FileDTO fileDTO = new FileDTO();
        BeanUtils.copyProperties(files,fileDTO);

        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            log.error("uuid{}的文件下载失败，原因：{}",fileUuid,e.getMessage());
            throw new RuntimeException(e);
        }
        log.info("uuid{}的文件获取成功",fileUuid);
    }

    /**
     * 展示文件
     * @param parentId
     * @param userId
     * @return
     */
    public List<FileVO> listFiles(Long parentId,Long userId) {
        parentId = normalizeParentId(parentId, userId);
        final Long temp = parentId;
        List<UserFileDTO> list = fileInfoMapper.getUserFileList(parentId,userId);
        List<FileVO> files = new ArrayList<>();
        list.forEach(fileiter -> {
            files.add( toFileVO( File_Info(fileiter.getFileUuid(),temp,userId) ));
        });
        return files;
    }

    /**
     * 探查 bucket 是否存在
     * @return
     */
    public boolean bucketExists() {
        try {
            minioclientUtil.bucketExists(NameConstant.DEFAULT_BUCKETNAME);
        } catch (Exception e) {
            log.error("没有这个桶：{}",NameConstant.DEFAULT_BUCKETNAME);
            throw new RuntimeException(e.getMessage());
        }
        return true;
    }

    /**
     * 重命名文件
     * @param fileUuid
     * @param newName
     */
    public FileVO renameFile(String fileUuid, Long parentId, String newName) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,parentId,userId);
        if(newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }
        if(userFileDTO == null) {
            log.warn("文件不存在{}",fileUuid);
            throw new RuntimeException("重命名失败");
        }
        if(userFileDTO.getStatus() == StatusConstant.DISABLE || !file_Status(fileUuid)) {
            log.warn("文件{}不可用:",fileUuid);
            throw new RuntimeException("重命名失败");
        }

        // TODO:1、后期可能需要添加类型检查 2、查重名的逻辑未来可能需要修改
        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getParentId(),userFileDTO.getUserId());
        for(UserFileDTO fileiter:files) {
            if(fileiter.getFileName().equals(newName) && fileiter.getDir() == userFileDTO.getDir() /*&& fileiter.getType.equals(file.getType())*/) {
                log.warn("同目录下存在同名文件");
                throw new RuntimeException("存在同名文件,重命名失败");
            }
        }

        int rows = fileInfoMapper.updateName(fileUuid,newName,userId,LocalDateTime.now());
        if(rows == 0) {throw new RuntimeException("重命名失败");}

        return toFileVO(File_Info(fileUuid,parentId,userId));
        //return toFileVO(userFileDTO);
    }

    public void deleteOSS(UserFileDTO userFileDTO) {
        Long userId = BaseContext.getCurrentId();
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userId);
        try {
            minioclientUtil.removeObject(file);
        } catch (Exception e) {
            log.warn("删除文件{}失败！:",file.getFileUuid());
            throw new RuntimeException("删除失败！原因：" + e.getMessage());
        }
        fileInfoMapper.delete_fileinfo_ByfileUuid(userFileDTO.getFileUuid());
        return ;
    }

    public boolean deleteFile(UserFileDTO userFileDTO) {
        Long userId = BaseContext.getCurrentId();
        // 获取file_info信息
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userId);
        try {
            //int rows = fileInfoMapper.deleteByfileUuid(userFileDTO.getFileUuid(),userFileDTO.getParentId(), userId);
            // 简化删除操作。
            int rows = fileInfoMapper.deleteByFileId(userFileDTO.getId(),userId);
            if(rows == 0) {
                log.warn("删除文件{}失败！:",file.getFileUuid());
                throw new RuntimeException("删除失败！原因：数据库信息未删除");
            }
            fileInfoMapper.updateFileCount(userFileDTO.getFileUuid(), -1);
        } catch (Exception e) {
            log.warn("删除文件{}失败！:",file.getFileUuid());
            throw new RuntimeException("删除失败！原因：" + e.getMessage());
        }
        log.info("删除文件{}的文件元数据成功！",userFileDTO.getFileUuid());
        if(fileInfoMapper.getFileCount(userFileDTO.getFileUuid()) == 0) deleteOSS(userFileDTO);
        return StatusConstant.SUCCESS;
    }

    /**
     * 删除某个单一文件的总入口
     * @param fileUuid
     * @return
     */

    public boolean deleteFiles(String fileUuid,Long parentId) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,parentId,userId);
        if(userFileDTO == null) {
            log.warn("文件{}不存在",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(!userId.equals(userFileDTO.getUserId()) || userFileDTO.getStatus() == 0) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(userFileDTO.getDir() == 1) {
            // 目录
            try {
                batchDelete(userFileDTO.getId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return StatusConstant.SUCCESS;
        }
        else {
            // 文件，直接删除即可。
            deleteFile(userFileDTO);
            return StatusConstant.SUCCESS;
        }
    }

    /**
     * 批量删除
     * @param deleteList
     * @return
     */
    public Boolean deletelot(List<UserFileDTO> deleteList) {
        Long userId = BaseContext.getCurrentId();
        deleteList.forEach(file -> {
            if(!file.getUserId().equals(userId)) {
                log.warn("文件所属对象不一致,删除失败");
                throw new RuntimeException("文件删除失败!");
            }
        });
        deleteList.forEach(file -> {
            if(file.getDir() == 1) {
                batchDelete(file.getId());
            }
            else {
                deleteFile(file);
            }
        });
        return true;
    }

    /**
     * 递归删除
     * @param fileUuid
     */
    private void batchDelete(String fileUuid,Long parentId) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,parentId,userId);
        // 先检查状态
        if(userFileDTO.getStatus() == 0) {
            log.warn("状态错误");
            throw new RuntimeException("文件不可用");
        }

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userFileDTO.getUserId());
        files.forEach(fileiter -> {
            if(fileiter.getDir() == 1) batchDelete(fileiter.getId());
            else deleteFile(fileiter);
        });
        // 递归结束，要删除自己。
        // 简化删除操作。
        //fileInfoMapper.deleteByfileUuid(fileUuid,userId);
        fileInfoMapper.deleteByFileId(userFileDTO.getId(),userId);
        return ;
    }

    /*
    简化了删除的操作（删除依据变更：uuid -> id）
     */
    private void batchDelete(Long id) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileId(id,userId);

        if(userFileDTO.getStatus() == 0) {
            log.warn("状态错误");
            throw new RuntimeException("文件不可用");
        }

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userFileDTO.getUserId());
        files.forEach(fileiter -> {
            if(fileiter.getDir() == 1) batchDelete(fileiter.getId());
            else deleteFile(fileiter);
        });
    }

    /**
     * 新建文件
     * @param isDir
     * @param parentId
     * @param name
     * @return
     */
    public FileVO makefile(int isDir,Long parentId,String name,String type) {
        Long userId = BaseContext.getCurrentId();
        if(name == null || name.length() == 0) {
            log.warn("文件名不合法:{}",name);
            throw new RuntimeException("文件名不合法");
        }
        parentId = normalizeParentId(parentId, userId);

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(parentId,userId);
        files.forEach(fileiter -> {
            if(fileiter.getFileName().equals(name) && (int)fileiter.getDir() == (int)isDir) {
                log.warn("同目录下有重名文件");
                throw new RuntimeException("存在同名文件,请重试");
            }
        });

        File existsFile = fileInfoMapper.findFileByName(name,userId,parentId);
        if(existsFile != null) {
            log.warn("文件名已存在");
            throw new RuntimeException("文件名已存在！");
        }

        if(isDir == 0) {
            File file = File.builder()
                    .fileUuid(UuidUtil.randomUuid())
                    .parentId(parentId)
                    .userId(userId)
                    .size(0L)
                    .name(name)
                    .type(type)
                    .status(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now()).build();

            UserFileDTO userFileDTO = UserFileDTO.builder()
                    .fileUuid(file.getFileUuid())
                    .userId(userId)
                    .fileName(name)
                    .status(1)
                    .parentId(parentId)
                    .path(getPath(parentId,userId) + file.getName())
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
            fileInfoMapper.updateFileCount(file.getFileUuid(),1);
            return toFileVO(file);
        }
        else {
            String uuid = UuidUtil.randomUuid();
            File file = File.builder()
                    .fileUuid(uuid)
                    .parentId(parentId)
                    .userId(userId)
                    .size(0L)
                    .dir(true)
                    .name(name)
                    .type("dir")
                    .status(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now()).build();

            UserFileDTO userFileDTO = UserFileDTO.builder()
                    .fileUuid(uuid)
                    .userId(userId)
                    .fileName(name)
                    .status(1)
                    .Dir(1)
                    .parentId(parentId)
                    .createtime(LocalDateTime.now())
                    .updatetime(LocalDateTime.now())
                    .build();
            fileInfoMapper.insertFile_User(userFileDTO);
            fileInfoMapper.updatePath(userFileDTO.getId(), userFileDTO.getFileUuid(), getPath(userFileDTO.getId(),userId),userId);
            return toFileVO(file);
        }
    }

    /**
     * 移动文件
     * @param sourceplace
     * @param targetplace
     * @return
     */
    public Boolean movefiles(Long sourceplace, Long targetplace) {
        /*
        源位置，目标位置（的file_id）
        移动文件，只需要更改他的父节点即可。所以我们只需要知道targetplace自身的fileid即可.
        （targetplace本质上应该是一个fileid/目录，因为查询目录信息需要询问后端，所以当前所处目录由前端发送，需要校验）
        注意在此之前需要审查各项数据是否合法
         */
        Long userId = BaseContext.getCurrentId();
        UserFileDTO files = fileInfoMapper.getByFileId(sourceplace,userId);
        UserFileDTO filet = fileInfoMapper.getByFileId(targetplace,userId);
        if(files == null || filet == null) {
            log.warn("文件归属错误!");
            throw new RuntimeException("移动失败!");
        }
        if(files.getStatus() == 0 || filet.getStatus() == 0) {
            log.warn("文件状态错误!");
            throw new RuntimeException("移动失败!");
        }
        if(filet.getDir() == 0) {
            log.warn("目标位置不属于文件夹");
            throw new RuntimeException("移动失败");
        }

        if(files.getDir() == 0) {
            // 设置源文件的父节点为目标位置的父节点
            files.setParentId( normalizeParentId( filet.getId(),filet.getUserId() ) );

            int rows = fileInfoMapper.updateParent(files.getId(),filet.getId(),LocalDateTime.now());
            if(rows == 0) {
                log.warn("数据库修改失败!");
                throw new RuntimeException("移动失败!");
            }
            log.info("文件移动成功!");
            return true;
        }
        else {
            files.setParentId( normalizeParentId( filet.getId(),filet.getUserId() ) );
            fileInfoMapper.updateParent(files.getId(),files.getFileUuid(),files.getParentId(),userId);
            /*
               修改子目录/文件路径
             */
            Queue<UserFileDTO> queue = new LinkedList<>();
            queue.offer(fileInfoMapper.getByFileUuid(files.getFileUuid(),files.getUserId()));
            while(!queue.isEmpty()) {
                UserFileDTO userFileDTO = queue.poll();
                if(userFileDTO.getDir() == 0) {
                    userFileDTO.setPath(getPath(userFileDTO.getId(),userId));
                    fileInfoMapper.updatePath(userFileDTO.getId(), userFileDTO.getFileUuid(), userFileDTO.getPath(), userId);
                    continue;
                }
                List<UserFileDTO> list = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userId);
                list.forEach(iter -> {
                    queue.offer(iter);
                });
                userFileDTO.setPath(getPath(userFileDTO.getId(),userId));
                fileInfoMapper.updatePath(userFileDTO.getId(),userFileDTO.getFileUuid(),userFileDTO.getPath(),userId);
            }
        }
        return true;
    }
}
