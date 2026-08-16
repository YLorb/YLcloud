package com.ylcloud.workflow.client;

import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;

import java.util.UUID;

public record WorkflowOperationResponse(
        String contractVersion,
        UUID runId,
        WorkflowRunStatus status
) {
}
