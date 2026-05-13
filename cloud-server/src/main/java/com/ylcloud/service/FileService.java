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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

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
        if(parentId == null) parentId = 0L;
        if(parentId > 0 && !fileInfoMapper.ParentIdExist(parentId,BaseContext.getCurrentId())) {
            log.info("尝试访问一个不存在的目录");
            throw new RuntimeException("目录不存在");
        }


        String md5;
        String hash;
        try {
            md5 = Md5Util.md5(uploadFile.getInputStream());
            hash = HashUtil.sha256(uploadFile.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("文件摘要计算失败", e);
        }

        log.info("文件名：{}，MD5：{}，hash：{}", uploadFile.getOriginalFilename(), md5, hash);

        File existingFile = fileInfoMapper.getByHash(hash);
        if (existingFile != null && existingFile.getParentId().equals(parentId)) {
            log.info("文件已存在，返回已有文件信息");
            return toFileVO(existingFile);
        }
        String fileUuid = UuidUtil.randomUuid();
        File file = File.builder()
                .userId(BaseContext.getCurrentId())
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
     * @param file_uuid
     * @param response
     */
    public void downloadFile(String file_uuid, HttpServletResponse response) {
        File file = fileInfoMapper.getByFileUuid(file_uuid);

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
            log.error("uuid{}的文件下载失败，原因：{}",file_uuid,e.getMessage());
            throw new RuntimeException(e);
        }
        log.info("uuid{}的文件获取成功",file_uuid);
    }

    /**
     * 展示文件
     * @param userId
     * @return
     */
    public List<FileVO> listFiles(Long userId) {
        return fileInfoMapper.listFileByUserId(userId);
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
        FileVO fileVO = new FileVO();
        File file = fileInfoMapper.getByFileUuid(fileUuid);
        Long userId = BaseContext.getCurrentId();
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
        int rows = fileInfoMapper.updateName(fileUuid,newName,BaseContext.getCurrentId(),LocalDateTime.now());
        if(rows == 0) {throw new RuntimeException("重命名失败");}

        file = fileInfoMapper.getByFileUuid(fileUuid);
        return toFileVO(file);
    }

    //public void deleteFolder(Long folderId)

    /**
     * 删除文件
     * @param fileUuid
     * @return
     */

    public boolean deleteFile(String fileUuid) {
        File file = fileInfoMapper.getByFileUuid(fileUuid);
        Long userId = BaseContext.getCurrentId();
        if(file == null) {
            log.warn("文件{}不存在",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(file.getStatus() == 0 || !userId.equals(file.getUserId())) {
            log.warn("文件{}不可用",fileUuid);
            throw new RuntimeException("删除失败");
        }
        if(file.getDir()) {
            // 软删除，根目录设置为 -1
            file.setParentId(-1L);
            /*try {
                batchDelete(fileUuid);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }*/
            return StatusConstant.SUCCESS;
        }
        //TODO：将待删除的文件放入一个待删除的队列（亦或是加入一个检查事件），定时扫描，异步处理。
        //TODO: 没有递归处理文件夹
        //先删除文件再删除元数据
        try {
            minioclientUtil.removeObject(file);
        } catch (Exception e) {
            log.warn("删除文件{}失败！:",fileUuid);
            throw new RuntimeException("删除失败！原因：" + e.getMessage());
        }

        int rows = fileInfoMapper.deleteByfileUuid(fileUuid,userId);
        if(rows == 0) {
            log.warn("删除文件{}的文件元数据失败！",fileUuid);
            throw new RuntimeException("删除失败");
        }
        log.info("删除文件{}的文件元数据成功！",fileUuid);
        return StatusConstant.SUCCESS;
    }

    /**
     * 批量删除
     * @param fileUuid
     */
    private void batchDelete(String fileUuid) {

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

        // TODO：文件名需要单独成表或者放置在某一数据结构（哈希表）中。
        File existsFile = fileInfoMapper.findFileByName(name,userId);
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
}
