package com.ylcloud.DTO;

import lombok.Data;

@Data
public class AuditQueryDTO {
    private String eventType;
    private Long subjectId;
    private String targetType;
    private String targetId;
    private String traceId;
    private String from;
    private String to;
    private Integer limit;
}
