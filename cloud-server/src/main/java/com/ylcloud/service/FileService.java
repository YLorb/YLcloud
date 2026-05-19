package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.constant.NameConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.File;
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
import java.util.List;

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

    private String getFileType(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        if (index == -1 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1);
    }

    private String getPath(String fileUuid,Long userId) {
        Long father = null;
        File file_now = fileInfoMapper.getByFileUuid(fileUuid,userId);
        String path = file_now.getName();
        if(file_now.getParentId() == 0L) {
            return "/";
        }
        father = normalizeParentId(file_now.getParentId(),userId);
        //File file_father = fileInfoMapper.getByFileUuid();
        path = getPath(String.valueOf(father),userId) + path;
        return path;
    }

    /**
     * parentid 校验
     * @param parentId
     * @param userId
     * @return
     */

    private Long normalizeParentId(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            File root = fileInfoMapper.getRootDirByUserId(userId);
            if (root != null) {
                return root.getFileId();
            }

            LocalDateTime now = LocalDateTime.now();
            root = File.builder()
                    .fileUuid(UuidUtil.randomUuid())
                    .dir(true)
                    .userId(userId)
                    .parentId(0L)
                    .name("/")
                    .type("dir")
                    .size(0L)
                    .path("/")
                    .status(1)
                    .createTime(now)
                    .updateTime(now)
                    .build();
            fileInfoMapper.insertFileInfo(root);
            return root.getFileId();
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
        if (uploadFile == null || uploadFile.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }
        Long userId = BaseContext.getCurrentId();
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

        File existingFile = fileInfoMapper.getByHash(hash,parentId,userId);
        if (existingFile != null && existingFile.getParentId().equals(parentId)) {
            log.info("文件已存在，返回已有文件信息");
            return toFileVO(existingFile);
        }
        String fileUuid = UuidUtil.randomUuid();
        File file = File.builder()
                .userId(userId)
                .name(uploadFile.getOriginalFilename())
                .type(getFileType(uploadFile.getOriginalFilename()))
                .size(uploadFile.getSize())
                .fileUuid(fileUuid)
                .hash(hash)
                .md5(md5)
                .dir(false)
                .parentId(parentId)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        log.info("文件{}基本信息设置完成，现在开始上传",uploadFile.getName());

        try {
            minioclientUtil.putObject(uploadFile,file.getFileUuid());
        } catch (Exception e) {
           log.error("uuid编号{}文件上传失败，原因：{}",file.getFileUuid(),e.getMessage());
           throw new RuntimeException(e);
        }
        log.info("uuid编号{}文件上传完成，正在存储文件信息",file.getFileUuid());
        log.info("文件信息已补全");
        fileInfoMapper.insertFileInfo(file);
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
        File file = fileInfoMapper.getByFileUuid(fileUuid,userId);

        if(file == null || file.getStatus().equals(StatusConstant.DISABLE)) {
            throw new RuntimeException("文件不存在或不可用");
        }

        if(!file.getUserId().equals(BaseContext.getCurrentId())) {
            log.warn("编号为{}的用户试图通过伪造 uuid 套取文件",BaseContext.getCurrentId());
            throw new RuntimeException("文件不存在");
        }

        FileDTO fileDTO = new FileDTO();
        BeanUtils.copyProperties(file,fileDTO);

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
     * @param userId
     * @return
     */
    /*public List<FileVO> listFiles(Long userId) {
        return fileInfoMapper.listFileByUserId(userId);
    }*/

    /**
     * 展示文件
     * @param parentId
     * @param userId
     * @return
     */
    public List<FileVO> listFiles(Long userId,Long parentId) {
        parentId = normalizeParentId(parentId, userId);
        return fileInfoMapper.listFileVOByparentId(parentId,userId);
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
        FileVO fileVO = new FileVO();
        File file = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(newName == null || newName.trim().isEmpty()) {
            throw new RuntimeException("文件名不能为空");
        }
        if(file == null) {
            log.warn("文件不存在{}",fileUuid);
            throw new RuntimeException("重命名失败");
        }
        if(file.getStatus() == StatusConstant.DISABLE) {
            log.warn("文件{}不可用:",fileUuid);
            throw new RuntimeException("重命名失败");
        }
        if(!userId.equals(file.getUserId())) {
            log.warn("文件{}所属错误",fileUuid);
            throw new RuntimeException("重命名失败");
        }

        List<File> files = fileInfoMapper.listFileByparentId(file.getParentId(),file.getUserId());
        for(File fileiter:files) {
            if(fileiter.getName().equals(newName) && fileiter.getDir() == file.getDir() /*&& fileiter.getType.equals(file.getType())*/) {
                log.warn("同目录下存在同名文件"); // TODO:后期可能需要添加类型检查
                throw new RuntimeException("存在同名文件,重命名失败");
            }
        }

        int rows = fileInfoMapper.updateName(fileUuid,newName,BaseContext.getCurrentId(),LocalDateTime.now());
        if(rows == 0) {throw new RuntimeException("重命名失败");}

        file = fileInfoMapper.getByFileUuid(fileUuid,userId);
        return toFileVO(file);
    }

    //public void deleteFolder(Long folderId)
    public boolean deleteFile(File file) {
        Long userId = BaseContext.getCurrentId();
        //先删除文件再删除元数据
        try {
            minioclientUtil.removeObject(file);
        } catch (Exception e) {
            log.warn("删除文件{}失败！:",file.getFileUuid());
            throw new RuntimeException("删除失败！原因：" + e.getMessage());
        }

        int rows = fileInfoMapper.deleteByfileUuid(file.getFileUuid(),userId);
        if(rows == 0) {
            log.warn("删除文件{}的文件元数据失败！",file.getFileUuid());
            throw new RuntimeException("删除失败");
        }
        log.info("删除文件{}的文件元数据成功！",file.getFileUuid());
        return StatusConstant.SUCCESS;
    }

    /**
     * 删除文件
     * @param fileUuid
     * @return
     */

    public boolean deleteFiles(String fileUuid) {
        Long userId = BaseContext.getCurrentId();
        File file = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(file == null) {
            log.warn("文件{}不存在",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(file.getStatus() == 0 || !userId.equals(file.getUserId())) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(file.getDir()) {
            file.setParentId(-1L);
            try {
                batchDelete(fileUuid);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            fileInfoMapper.deleteByfileUuid(file.getFileUuid(),userId);
            return StatusConstant.SUCCESS;
        }
        else {
            deleteFile(file);
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
    public Boolean deletelot(List<File> deleteList) {
        Long userId = BaseContext.getCurrentId();
        deleteList.forEach(file -> {
            if(!file.getUserId().equals(userId)) {
                log.warn("文件所属对象不一致,删除失败");
                throw new RuntimeException("文件删除失败!");
            }
        });
        deleteList.forEach(file -> {
            if(file.getDir()) {
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
        File file = fileInfoMapper.getByFileUuid(fileUuid,userId);
        if(!file.getDir()) {
            deleteFile(file);
            return ;
        }
        List<File> files = fileInfoMapper.listFileByparentId(file.getFileId(),file.getUserId());
        files.forEach(fileiter -> {
            if(fileiter.getDir()) {
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
    public FileVO makefile(boolean isDir,Long parentId,String name,String type) {
        Long userId = BaseContext.getCurrentId();
        if(name == null || name.length() == 0) {
            log.warn("文件名不合法:{}",name);
            throw new RuntimeException("文件名不合法");
        }
        parentId = normalizeParentId(parentId, userId);

        List<File> files = fileInfoMapper.listFileByparentId(parentId,userId);
        files.forEach(fileiter -> {
            if(fileiter.getName().equals(name) && fileiter.getDir() == isDir) {
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
        if(!isDir) {
            file.setDir(false);
            try {
                minioclientUtil.putEmptyObject(file.getFileUuid());
            } catch (Exception e) {
                log.warn("创建空文件对象失败：{}",file.getFileUuid(),e);
                throw new RuntimeException("新建文件失败");
            }
        }
        else file.setDir(true);
        int rows = fileInfoMapper.insertFileInfo(file);
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
        File files = fileInfoMapper.getByFileId(sourceplace,userId);
        File filet = fileInfoMapper.getByFileId(targetplace,userId);
        if(files == null || filet == null) {
            log.warn("文件归属错误!");
            throw new RuntimeException("移动失败!");
        }
        if(!filet.getDir()) {
            log.warn("目标位置不属于文件夹");
            throw new RuntimeException("移动失败");
        }
        // 设置源文件的父节点为目标位置的父节点
        files.setParentId(filet.getFileId());

        int rows = fileInfoMapper.updateParent(files.getFileId(),filet.getFileId(),LocalDateTime.now());
        if(rows == 0) {
            log.warn("数据库修改失败!");
            throw new RuntimeException("移动失败!");
        }

        log.info("文件移动成功!");
        return true;
    }
}
