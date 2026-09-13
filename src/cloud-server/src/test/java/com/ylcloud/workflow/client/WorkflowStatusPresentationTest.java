package com.ylcloud.workflow.client;

import com.ylcloud.workflow.contract.WorkflowContracts.JavaMessageStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowStatusPresentationTest {
    @Test
    void mapsEveryWorkflowStatusToPrimaryStatusAndColor() {
        Map<WorkflowRunStatus, JavaMessageStatus> expected = Map.ofEntries(
                Map.entry(WorkflowRunStatus.QUEUED, JavaMessageStatus.QUEUED),
                Map.entry(WorkflowRunStatus.PLANNING, JavaMessageStatus.RUNNING),
                Map.entry(WorkflowRunStatus.VALIDATING, JavaMessageStatus.RUNNING),
                Map.entry(WorkflowRunStatus.RUNNING, JavaMessageStatus.RUNNING),
                Map.entry(WorkflowRunStatus.SUCCEEDED, JavaMessageStatus.SUCCESS),
                Map.entry(WorkflowRunStatus.DEGRADED, JavaMessageStatus.SUCCESS),
                Map.entry(WorkflowRunStatus.FAILED, JavaMessageStatus.FAILED),
                Map.entry(WorkflowRunStatus.TIMED_OUT, JavaMessageStatus.FAILED),
                Map.entry(WorkflowRunStatus.CANCELLED, JavaMessageStatus.FAILED),
                Map.entry(WorkflowRunStatus.ABANDONED, JavaMessageStatus.FAILED)
        );
        expected.forEach((status, primary) ->
                assertEquals(primary, WorkflowStatusPresentation.from(status).javaStatus()));
        assertEquals("yellow", WorkflowStatusPresentation.from(WorkflowRunStatus.DEGRADED).color());
        assertEquals("red", WorkflowStatusPresentation.from(WorkflowRunStatus.CANCELLED).color());
    }
}
