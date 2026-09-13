package com.ylcloud.VO;

import lombok.Data;

@Data
public class UserMemoryStatsVO {
    private Integer activeCount;
    private Integer pinnedCount;
    private Integer pendingCount;
    private Integer failedCount;
    private Long contextTokens;
    private Integer feedbackCount;
    private Integer helpfulCount;
}
