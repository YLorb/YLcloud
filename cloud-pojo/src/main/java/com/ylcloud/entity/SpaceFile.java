package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间文件树节点实体，对应 space_file 表。
 */
@Data
public class SpaceFile {
    private Long id;
    private Long spaceId;
    private String fileUuid;
    private String fileName;
    private Integer dir;
    private Long parentId;
    private String path;
    private Integer versionEnabled;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
