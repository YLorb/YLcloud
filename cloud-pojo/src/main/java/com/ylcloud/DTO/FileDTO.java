package com.ylcloud.DTO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileDTO {
    private Long fileId;

    private Long fileUuid;

    private boolean isDir;

    private Long userId;

    private Long parentId;

    private String name;

    private String type;

    private Long size;

    private String path;

    private String md5;

    private String hash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
