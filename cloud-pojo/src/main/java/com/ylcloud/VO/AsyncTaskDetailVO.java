package com.ylcloud.VO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户可见的后台任务详情。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AsyncTaskDetailVO extends AsyncTaskVO {
    private Integer successCount;
    private Integer failedCount;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private Long durationMs;
    private String terminalStage;
    private String terminalReason;
    private String completionSummary;
    private String taskDomain;
    private String resourceKey;
    private Long resourceVersion;
    private Integer attemptVersion;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastHeartbeatAt;
    private Boolean canRetry;
    private Boolean canCancel;
    private String operationReason;
    private Boolean legacy;
    private List<AsyncTaskAttemptVO> attempts;
}
