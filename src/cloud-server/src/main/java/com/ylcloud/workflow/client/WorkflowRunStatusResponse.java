package com.ylcloud.workflow.client;

import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkflowRunStatusResponse(
        String contractVersion,
        UUID runId,
        UUID executionId,
        int executionEpoch,
        WorkflowRunStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
