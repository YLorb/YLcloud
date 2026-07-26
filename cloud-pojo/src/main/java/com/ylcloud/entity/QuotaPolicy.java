package com.ylcloud.entity;

import lombok.Data;

@Data
public class QuotaPolicy {
    private Long groupId;
    private Long storageBytes;
    private Long maxFileBytes;
    private Long spaceLimit;
    private Long monthlyApiCalls;
    private Long monthlyModelTokens;
    private Long monthlyAgentTasks;
    private Long concurrentAgentTasks;
}
