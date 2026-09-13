package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserMemoryExtractionTask {
    private Long id;
    private Long assistantMessageId;
    private Long userId;
    private Long sessionId;
    private Long sourceMessageId;
    private String taskStatus;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String errorMessage;
    private Long asyncTaskId;
    private Long resourceVersion;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
