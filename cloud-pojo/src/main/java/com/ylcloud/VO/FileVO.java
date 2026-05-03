package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileVO {
    private Long fileId;

    private String fileUuid;

    private boolean isDir;

    private Long userId;

    private Long parentId;

    private String name;

    private String type;

    private Long size;

    private String hash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
