package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceKnowledgeAuditLog {
    private Long id;
    private Long spaceId;
    private Long operatorId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdTime;
}
