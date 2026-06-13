package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间实体，对应 spaces 表。
 */
@Data
public class Space {
    private Long id;
    private String name;
    private String description;
    private String type;
    private Long ownerId;
    private Long rootDirId;
    private Integer ragStatus;
    private Integer versionEnabled;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
