package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SecurityAuditEventVO {
    private Long eventId;
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
    private String ipAddress;
    private String detailJson;
    private String errorMessage;
    private String retentionPolicy;
    private LocalDateTime occurredAt;
}
