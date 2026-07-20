package com.ylcloud.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserMemoryItem {
    private Long id;
    private Long userId;
    private Long sourceSessionId;
    private Long sourceMessageId;
    private String memoryType;
    private String content;
    private String normalizedKey;
    private String contentHash;
    private String sourceHash;
    private BigDecimal confidence;
    private Boolean userConfirmed;
    private Boolean pinned;
    private LocalDateTime expiresAt;
    private Integer version;
    private String memoryStatus;
    private String embeddingStatus;
    private String qdrantPointId;
    private Long supersedesId;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String errorMessage;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
