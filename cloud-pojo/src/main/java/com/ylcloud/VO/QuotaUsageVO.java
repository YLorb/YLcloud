package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDate;

@Data
public class QuotaUsageVO {
    private String accountType;
    private Long referenceId;
    private LocalDate periodStart;
    private Long storageBytes;
    private Long storageLimitBytes;
    private Long fileCount;
    private Long spaceCount;
    private Long spaceLimit;
    private Long apiCalls;
    private Long apiCallLimit;
    private Long modelTokens;
    private Long modelTokenLimit;
    private Long agentTasks;
    private Long agentTaskLimit;
    private Long concurrentAgentTasks;
    private Long concurrentAgentTaskLimit;
}
