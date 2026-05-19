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

    private File File_Info(String fileUuid,Long userId) {
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

    private String setFileType(String fileName) {
        String suffix = fileName.substring(fileName.lastIndexOf("."));
        if(suffix == null) suffix = ".txt";
        return suffix;
    }

    /**
     * 获取文件路径
     * 通过不断询问父亲的名字实现。
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
        return userFileDTO.getPath() + userFileDTO.getFileName() + "/";
        /*UserFileDTO file_now = fileInfoMapper.getFileByFileId(fileId,userId);
        if(file_now != null) {
            return file_now.getPath();
        }
        if(file_now.getParentId() == 0L) {
            return "/";
        }
        Long father = normalizeParentId(file_now.getParentId(),userId);
        String path = ""; // TODO：逻辑未闭环，需要立即重构所有的逻辑。
        path = getPath(father,userId) + path;*/
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
     *  normalizeParentId 的 public 化
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
        FileDTO fileDTO = new FileDTO();
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

        File existingFile = fileInfoMapper.getFileByHash(hash,parentId,userId);
        if (existingFile != null && existingFile.getParentId().equals(parentId)) {
            log.info("文件已存在，返回已有文件信息");
            return toFileVO(existingFile);
        }
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

        if(exist != null) {
            file_user.setFileUuid(existingFile.getFileUuid());
            file_user.setDir(exist.getDir()?1:0);
            file_user.setPath(getPath(parentId,userId) + "/" + uploadFile.getOriginalFilename());
            file_user.setStatus(1);
            try {
                fileInfoMapper.insertFile_User(file_user);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return toFileVO(File_Info(file_user.getFileUuid(), userId));
        }

        try {
            minioclientUtil.putObject(uploadFile,file.getFileUuid());
        } catch (Exception e) {
           log.error("uuid编号{}文件上传失败，原因：{}",file.getFileUuid(),e.getMessage());
           throw new RuntimeException(e);
        }
        file_user.setPath(getPath(file_user.getParentId(),userId) + file_user.getFileName());
        fileInfoMapper.insertFileInfo(file);
        fileInfoMapper.insertFile_User(file_user);
        log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());
        log.info("文件信息已补全");
        return toFileVO(file);
    }

    /**
     * 下载文件
     * 通过 http response 响应体返回文件数据
     * @param fileUuid
     * @param response
     */
    public void downloadFile(String fileUuid, HttpServletResponse response) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);

        if(userFileDTO == null) {
            log.warn("未找到相关文件{},{}",fileUuid,userId);
            throw new RuntimeException("文件不存在");
        }

        if(!userFileDTO.getUserId().equals(userId)) {
            log.warn("编号为{}的用户试图通过伪造 uuid 套取文件",userId);
            throw new RuntimeException("文件不存在");
        }

        if(!file_Status(fileUuid)) {
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
        List<UserFileDTO> list = fileInfoMapper.getUserFileList(parentId,userId);
        List<FileVO> files = new ArrayList<>();
        list.forEach(fileiter -> {
            files.add( toFileVO( File_Info(fileiter.getFileUuid(),userId) ));
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
    public FileVO renameFile(String fileUuid, String newName) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }
        if(userFileDTO == null) {
            log.warn("文件不存在{}",fileUuid);
            throw new RuntimeException("重命名失败");
        }
        if(userFileDTO.getStatus() == StatusConstant.DISABLE) {
            log.warn("文件{}不可用:",fileUuid);
            throw new RuntimeException("重命名失败");
        }
        if(!userId.equals(userFileDTO.getUserId())) { // 理论上这样的问题不会存在？
            log.warn("文件{}所属错误",fileUuid);
            throw new RuntimeException("重命名失败");
        }

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getParentId(),userFileDTO.getUserId());
        for(UserFileDTO fileiter:files) {
            if(fileiter.getFileName().equals(newName) && fileiter.getDir() == userFileDTO.getDir() /*&& fileiter.getType.equals(file.getType())*/) {
                log.warn("同目录下存在同名文件"); // TODO:后期可能需要添加类型检查
                throw new RuntimeException("存在同名文件,重命名失败");
            }
        }

        int rows = fileInfoMapper.updateName(fileUuid,newName,userId,LocalDateTime.now());
        if(rows == 0) {throw new RuntimeException("重命名失败");}

        return toFileVO(File_Info(fileUuid,userId));
        //return toFileVO(userFileDTO);
    }

    //public void deleteFolder(Long folderId)
    /*可能需要给file_info添加一个计数器，记录有多少文件引用了这个桶的文件，没有文件引用时删除桶内文件*/
    public boolean deleteFile(UserFileDTO userFileDTO) {
        Long userId = BaseContext.getCurrentId();
        File file = fileInfoMapper.getFileByFileUuid(userFileDTO.getFileUuid(),userId);
        //先删除文件再删除元数据
        try {
            //minioclientUtil.removeObject(file);
            int rows = fileInfoMapper.deleteByfileUuid(userFileDTO.getFileUuid(), userId);
            if(rows == 0) {
                log.warn("删除文件{}失败！:",file.getFileUuid());
                throw new RuntimeException("删除失败！原因：数据库信息未删除");
            }
        } catch (Exception e) {
            log.warn("删除文件{}失败！:",file.getFileUuid());
            throw new RuntimeException("删除失败！原因：" + e.getMessage());
        }

        /*int rows = fileInfoMapper.deleteByfileUuid(userFileDTO.getFileUuid(),userId);
        if(rows == 0) {
            log.warn("删除文件{}的文件元数据失败！",userFileDTO.getFileUuid());
            throw new RuntimeException("删除失败");
        }*/
        log.info("删除文件{}的文件元数据成功！",userFileDTO.getFileUuid());
        return StatusConstant.SUCCESS;
    }

    /**
     * 删除文件
     * @param fileUuid
     * @return
     */

    public boolean deleteFiles(String fileUuid) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(userFileDTO == null) {
            log.warn("文件{}不存在",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(!userId.equals(userFileDTO.getUserId()) || userFileDTO.getStatus() == 0) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(userFileDTO.getDir() == 1) {
            userFileDTO.setParentId(-1L);
            try {
                batchDelete(fileUuid);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            fileInfoMapper.deleteByfileUuid(userFileDTO.getFileUuid(),userId);
            return StatusConstant.SUCCESS;
        }
        else {
            deleteFile(userFileDTO);
        }
        //TODO：将待删除的文件放入一个待删除的队列（亦或是加入一个检查事件），定时扫描，异步处理。
        //TODO: 没有递归处理文件夹
        return StatusConstant.SUCCESS;
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
                batchDelete(file.getFileUuid());
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
    private void batchDelete(String fileUuid) {
        Long userId = BaseContext.getCurrentId();
        UserFileDTO userFileDTO = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(userFileDTO.getDir() == 0) {
            deleteFile(userFileDTO);
            return ;
        }

        List<UserFileDTO> files = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userFileDTO.getUserId());
        files.forEach(fileiter -> {
            if(fileiter.getDir() == 1) {
                batchDelete(fileiter.getFileUuid());
                fileInfoMapper.deleteByfileUuid(fileiter.getFileUuid(),userId);
            }
            else deleteFile(fileiter);
        });
        return ;
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

        // TODO：文件名需要单独成表或者放置在某一数据结构（哈希表）中。
        File existsFile = fileInfoMapper.findFileByName(name,userId,parentId);
        if(existsFile != null) {
            log.warn("文件名已存在");
            throw new RuntimeException("文件名已存在！");
        }
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

        if(isDir == 0) {
            file.setDir(false);
            userFileDTO.setDir(0);
            try {
                minioclientUtil.putEmptyObject(file.getFileUuid());
            } catch (Exception e) {
                log.warn("创建空文件对象失败：{}",file.getFileUuid(),e);
                throw new RuntimeException("新建文件失败");
            }
        }
        else {
            file.setDir(true); userFileDTO.setDir(1);
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
        if(filet.getDir() == 0) {
            log.warn("目标位置不属于文件夹");
            throw new RuntimeException("移动失败");
        }

        if(files.getDir() == 0) {
            // 设置源文件的父节点为目标位置的父节点
            files.setParentId( normalizeParentId( filet.getParentId(),filet.getUserId() ) );

            int rows = fileInfoMapper.updateParent(files.getId(),filet.getId(),LocalDateTime.now());
            if(rows == 0) {
                log.warn("数据库修改失败!");
                throw new RuntimeException("移动失败!");
            }

            log.info("文件移动成功!");
            return true;
        }
        else {
            files.setParentId( normalizeParentId( filet.getParentId(),filet.getUserId() ) );
            fileInfoMapper.updateParent(files.getFileUuid(),files.getParentId(),userId);
            Queue<UserFileDTO> queue = new LinkedList<>();
            queue.offer(fileInfoMapper.getByFileUuid(files.getFileUuid(),files.getUserId()));
            while(!queue.isEmpty()) {
                UserFileDTO userFileDTO = queue.poll();
                if(userFileDTO.getDir() == 0) continue;
                List<UserFileDTO> list = fileInfoMapper.listFileByparentId(userFileDTO.getId(),userId);
                list.forEach(iter -> {
                    queue.offer(iter);
                });
                userFileDTO.setPath(getPath(userFileDTO.getId(),userId));
                fileInfoMapper.updatePath(userFileDTO.getFileUuid(),userFileDTO.getPath(),userId);
            }
        }
        return true;
    }
}
