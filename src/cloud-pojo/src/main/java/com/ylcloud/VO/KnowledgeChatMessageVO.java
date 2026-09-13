package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChatMessageVO {
    private Long id;
    private Long sessionId;
    private Long sequenceNo;
    private String role;
    private String content;
    private String citationsJson;
    private String taskStatus;
    private String errorMessage;
    private Integer retryCount;
    private String workflowRunId;
    private String workflowExecutionId;
    private Integer workflowExecutionEpoch;
    private String workflowStatus;
    private Boolean degraded;
    private String statusColor;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
