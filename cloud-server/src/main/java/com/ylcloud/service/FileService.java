package com.ylcloud.service;

import com.ylcloud.VO.FileVO;
import com.ylcloud.entity.File;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Slf4j
public class FileService {

    private final FileInfoMapper fileInfoMapper;

    public FileService(FileInfoMapper fileInfoMapper) {
        this.fileInfoMapper = fileInfoMapper;
    }

    public FileVO upload(MultipartFile uploadFile) {
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
        fileVO.setName(uploadFile.getOriginalFilename());
        fileVO.setType(getFileType(uploadFile.getOriginalFilename()));
        fileVO.setSize(uploadFile.getSize());
        fileVO.setHash(hash);
        fileVO.setDir(false);
        return fileVO;
    }

    private FileVO toFileVO(File file) {
        FileVO fileVO = new FileVO();
        fileVO.setFileId(file.getFileId());
        fileVO.setFileUuid(file.getFileUuid());
        fileVO.setDir(file.isDir());
        fileVO.setUserId(file.getUserId());
        fileVO.setParentId(file.getParentId());
        fileVO.setName(file.getName());
        fileVO.setType(file.getType());
        fileVO.setSize(file.getSize());
        fileVO.setHash(file.getHash());
        fileVO.setCreateTime(file.getCreateTime());
        fileVO.setUpdateTime(file.getUpdateTime());
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
