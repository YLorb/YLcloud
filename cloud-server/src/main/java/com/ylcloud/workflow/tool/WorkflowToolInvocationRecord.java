package com.ylcloud.workflow.tool;

import lombok.Data;

@Data
public class WorkflowToolInvocationRecord {
    private String invocationId;
    private String runId;
    private String executionId;
    private String nodeId;
    private Long userId;
    private Long apiKeyId;
    private Long sessionId;
    private String toolName;
    private String riskLevel;
    private String argumentsHash;
    private String invocationStatus;
    private String responseJson;
    private String errorCode;
}
