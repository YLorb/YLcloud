package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UnifiedTaskAttempt {
    private Long id;
    private Long taskId;
    private Integer attemptVersion;
    private String triggerType;
    private String workerId;
    private String leaseToken;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime finishedAt;
    private String failureType;
    private String failureCode;
    private String failureMessage;
    private Boolean retryable;
    private LocalDateTime nextRetryAt;
    private Long operatorId;
    private String operatorRemark;
    private Long durationMs;
}
