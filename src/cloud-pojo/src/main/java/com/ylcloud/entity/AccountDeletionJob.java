package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AccountDeletionJob {
    private Long id;
    private Long userId;
    private String jobKey;
    private String status;
    private String currentStep;
    private String stepResultJson;
    private Long asyncTaskId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String lastError;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
