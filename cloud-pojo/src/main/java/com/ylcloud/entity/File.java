package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class File {
    private Long fileId;

    private String fileUuid;

    private boolean isDir;

    private Long userId;

    private Long parentId;

    private String name;

    private String type;

    private Long size;

    private String path;

    private String md5;

    private String hash;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
