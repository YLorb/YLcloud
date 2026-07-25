package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminResourceGrant {
    private Long id;
    private Long grantorId;
    private Long adminUserId;
    private String resourceType;
    private Long resourceId;
    private String action;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
