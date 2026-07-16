package com.ylcloud.VO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

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
}
