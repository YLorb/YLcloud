package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.VO.FileVO;
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
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class FileService {

    private FileInfoMapper fileInfoMapper;

    private MinioclientUtil minioclientUtil;

    /**
     * 上传文件
     * @param uploadFile
     * @return
     */
    @PostMapping("/upload")
    public FileVO upload(MultipartFile uploadFile) {
        FileDTO fileDTO = new FileDTO();
        if (uploadFile == null || uploadFile.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
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

        File existingFile = fileInfoMapper.getByMD5(md5);
        if (existingFile != null) {
            log.info("文件已存在，返回已有文件信息");
            return toFileVO(existingFile);
        }

        FileVO fileVO = new FileVO();
        fileDTO.setName(uploadFile.getOriginalFilename());
        fileDTO.setType(getFileType(uploadFile.getOriginalFilename()));
        fileDTO.setSize(uploadFile.getSize());
        fileDTO.setFileUuid(UuidUtil.randomUuid());
        fileDTO.setHash(hash);
        fileDTO.setMd5(md5);
        fileDTO.setDir(false);
        log.info("文件{}基本信息设置完成，现在开始上传",uploadFile.getName());

        try {
            minioclientUtil.putObject(uploadFile,fileDTO.getFileUuid());
        } catch (Exception e) {
           log.error("uuid编号{}文件上传失败，原因：{}",fileDTO.getFileUuid(),e.getMessage());
           throw new RuntimeException(e);
        }
        log.info("uuid编号{}文件上传完成，正在存储文件信息",fileDTO.getFileUuid());
        fileDTO.setStatus(1); // 文件可用
        fileDTO.setUserId(BaseContext.getCurrentId());
        //fileVO.setName(uploadFile.getOriginalFilename());
        //fileVO.setType(getFileType(uploadFile.getOriginalFilename()));
        //fileVO.setSize(uploadFile.getSize());
        //fileVO.setHash(hash);
        //fileVO.setDir(false);
        return toFileVO(fileDTO);
    }

    /**
     * 通过 http response 响应体返回文件数据
     * @param file_uuid
     * @param response
     */
    private void downloadFile(String file_uuid, HttpServletResponse response) {
        File file = fileInfoMapper.getByFileUuid(file_uuid);

        if(file == null || file.getStatus() == StatusConstant.DISABLE) {
            throw new RuntimeException("文件不存在或不可用");
        }

        if(file.getUserId() != BaseContext.getCurrentId()) {
            log.warn("编号为{}的用户试图通过伪造 uuid 套取文件",BaseContext.getCurrentId());
            throw new RuntimeException("文件不存在");
        }

        FileDTO fileDTO = new FileDTO();
        BeanUtils.copyProperties(fileDTO,file);

        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            log.error("uuid{}的文件下载失败，原因：{}",file_uuid,e);
            throw new RuntimeException(e);
        }
        log.info("uuid{}的文件获取成功",file_uuid);
    }

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
}
