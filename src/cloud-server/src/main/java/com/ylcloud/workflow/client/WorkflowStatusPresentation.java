package com.ylcloud.workflow.client;

import com.ylcloud.workflow.contract.WorkflowContracts;
import com.ylcloud.workflow.contract.WorkflowContracts.JavaMessageStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;

/** 前端先展示一级 Java 状态，详情展示二级 Workflow 状态。 */
public record WorkflowStatusPresentation(
        JavaMessageStatus javaStatus,
        WorkflowRunStatus workflowStatus,
        boolean degraded,
        String color
) {
    public static WorkflowStatusPresentation from(WorkflowRunStatus status) {
        WorkflowContracts.StatusMapping mapping = WorkflowContracts.mapJavaStatus(status);
        String color = mapping.degraded() ? "yellow" : switch (mapping.javaStatus()) {
            case QUEUED -> "purple";
            case RUNNING -> "blue";
            case SUCCESS -> "green";
            case FAILED -> "red";
        };
        return new WorkflowStatusPresentation(
                mapping.javaStatus(), mapping.workflowStatus(), mapping.degraded(), color
        );
    }
}
