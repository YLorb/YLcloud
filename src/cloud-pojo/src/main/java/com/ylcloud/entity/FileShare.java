package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileShare {
    private Long id;

    private String shareCode;

    private Long userFileId;

    private String fileUuid;

    private Long ownerId;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
