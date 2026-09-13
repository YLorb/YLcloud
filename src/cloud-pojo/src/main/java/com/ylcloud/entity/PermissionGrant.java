package com.ylcloud.entity;

import lombok.Data;

@Data
public class PermissionGrant {
    private Long subjectId;
    private String permissionKey;
    private Integer allowed;
}
