package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AsyncTaskAttemptVO {
    private Integer attemptVersion;
    private String triggerType;
    private String workerId;
    private String leaseTokenMasked;
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
