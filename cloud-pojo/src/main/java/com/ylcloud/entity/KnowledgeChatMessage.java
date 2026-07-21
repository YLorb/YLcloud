package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChatMessage {
    private Long id;
    private Long sessionId;
    private Long userId;
    private Long sequenceNo;
    private Long sourceMessageId;
    private String role;
    private String content;
    private String citationsJson;
    private String taskStatus;
    private String errorMessage;
    private String requestKey;
    private String requestJson;
    private String contextSnapshotJson;
    private String contextHash;
    private Integer contextVersion;
    private Integer contextTokenCount;
    private Integer retryCount;
    private String workflowRunId;
    private String workflowExecutionId;
    private Integer workflowExecutionEpoch;
    private String workflowStatus;
    private Boolean workflowDegraded;
    private String workflowResultHash;
    private String workflowSnapshotHash;
    private String workflowResultJson;
    private String generationStatus;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
