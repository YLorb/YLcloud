package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PermissionGroup {
    private Long id;
    private String name;
    private String description;
    private Integer systemGroup;
    private Integer userCount;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
