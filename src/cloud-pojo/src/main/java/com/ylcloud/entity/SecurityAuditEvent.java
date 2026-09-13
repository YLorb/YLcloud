package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SecurityAuditEvent {
    private Long eventId;
    private String eventKey;
    private String eventType;
    private String subjectType;
    private Long subjectId;
    private String subjectName;
    private String targetType;
    private String targetId;
    private String targetName;
    private String action;
    private String result;
    private String traceId;
    private String requestId;
    private String ipAddress;
    private String userAgent;
    private String detailJson;
    private String errorMessage;
    private String retentionPolicy;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
