package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuditRetentionConfig {
    private String configKey;
    private Integer retentionDays;
    private Boolean permanent;
    private String description;
    private LocalDateTime updatedAt;
}
