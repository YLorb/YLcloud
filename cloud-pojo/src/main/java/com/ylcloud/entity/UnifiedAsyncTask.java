package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UnifiedAsyncTask {
    private Long id;
    private String taskKey;
    private String taskDomain;
    private String taskType;
    private String payloadJson;
    private String resultJson;
    private Long createdBy;
    private Long spaceId;
    private String resourceKey;
    private Long resourceVersion;
    private String status;
    private Integer attemptVersion;
    private String nextTriggerType;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private String leaseToken;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime cancelRequestedAt;
    private Long cancelRequestedBy;
    private String cancelReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String lastErrorType;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
