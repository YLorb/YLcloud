package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件历史版本实体，对应 file_version 表。
 */
@Data
public class FileVersion {
    private Long id;
    private String fileUuid;
    private Integer versionNo;
    private String minioVersionId;
    private String fileName;
    private String fileHash;
    private String fileMd5;
    private String fileType;
    private Long fileSize;
    private String changeNote;
    private Long createdBy;
    private Integer current;
    private Integer status;
    private LocalDateTime createtime;
}
