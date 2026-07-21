package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.workflow.client.WorkflowRunRequestFactory.PreparedWorkflowRun;
import com.ylcloud.workflow.contract.WorkflowContracts.KnowledgeScopeDecision;
import com.ylcloud.workflow.contract.WorkflowContracts.RetrievalTrace;
import com.ylcloud.workflow.contract.WorkflowContracts.RunBudget;
import com.ylcloud.workflow.contract.WorkflowContracts.SnapshotDraft;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunAccepted;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunCreateRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowType;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowMessageLifecycleServiceTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EXECUTION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final WorkflowHttpClient client = mock(WorkflowHttpClient.class);
    private final WorkflowRunRequestFactory requestFactory = mock(WorkflowRunRequestFactory.class);
    private final WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);
    private final KnowledgeChatMessageMapper mapper = mock(KnowledgeChatMessageMapper.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private WorkflowMessageLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowMessageLifecycleService(client, requestFactory, answerGenerator, mapper, objectMapper);
    }

    @Test
    void startBindsAcceptedRunToQueuedAssistant() {
        KnowledgeChatMessage message = message("QUEUED", null, null, null);
        when(mapper.getTaskById(9L)).thenReturn(message);
        when(requestFactory.create(message)).thenReturn(new PreparedWorkflowRun(createRequest(), "idem-9"));
        when(client.createRun(any(), eq("idem-9"))).thenReturn(accepted(1, EXECUTION_ID));

        service.start(9L);

        verify(mapper).bindWorkflowRun(9L, RUN_ID.toString(), EXECUTION_ID.toString(), 1);
    }

    @Test
    void reconcileFreezesResultThenUsesJavaToGenerateFinalAnswer() throws Exception {
        KnowledgeChatMessage running = message("RUNNING", "RUNNING", EXECUTION_ID.toString(), 1);
        WorkflowResult result = result(WorkflowRunStatus.DEGRADED, EXECUTION_ID, 1);
        KnowledgeChatMessage frozen = message("RUNNING", "DEGRADED", EXECUTION_ID.toString(), 1);
        frozen.setGenerationStatus("PENDING");
        frozen.setWorkflowResultJson(objectMapper.writeValueAsString(result));
        when(mapper.getTaskById(9L)).thenReturn(running, frozen, frozen);
        when(client.getRun(RUN_ID)).thenReturn(status(WorkflowRunStatus.DEGRADED, EXECUTION_ID, 1));
        when(client.getResult(RUN_ID)).thenReturn(result);
        when(mapper.queueWorkflowGeneration(eq(9L), eq(RUN_ID.toString()), eq(EXECUTION_ID.toString()),
                eq(1), eq("DEGRADED"), eq(true), any(), any(), any())).thenReturn(1);
        when(mapper.claimWorkflowGeneration(9L, 1)).thenReturn(1);
        when(answerGenerator.generate(frozen, result)).thenReturn("Java final answer");

        service.reconcile(9L);

        verify(answerGenerator).generate(frozen, result);
        verify(mapper).markWorkflowGenerated(9L, 1, "Java final answer");
    }

    @Test
    void ignoresOldExecutionStatusAndCannotOverwriteCurrentEpoch() {
        UUID currentExecution = UUID.fromString("33333333-3333-4333-8333-333333333333");
        KnowledgeChatMessage current = message("RUNNING", "RUNNING", currentExecution.toString(), 2);
        when(mapper.getTaskById(9L)).thenReturn(current);
        when(client.getRun(RUN_ID)).thenReturn(status(WorkflowRunStatus.SUCCEEDED, EXECUTION_ID, 1));

        service.reconcile(9L);

        verify(client, never()).getResult(any());
        verify(mapper, never()).queueWorkflowGeneration(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any());
    }

    @Test
    void retryAfterJavaGenerationFailureReusesFrozenResultOnly() throws Exception {
        WorkflowResult result = result(WorkflowRunStatus.SUCCEEDED, EXECUTION_ID, 1);
        KnowledgeChatMessage message = message("QUEUED", "SUCCEEDED", EXECUTION_ID.toString(), 1);
        message.setRetryCount(1);
        message.setGenerationStatus("PENDING");
        message.setWorkflowResultJson(objectMapper.writeValueAsString(result));
        when(mapper.getTaskById(9L)).thenReturn(message, message);
        when(mapper.claimWorkflowGeneration(9L, 1)).thenReturn(1);
        when(answerGenerator.generate(message, result)).thenReturn("retry answer");

        service.retry(9L);

        verify(client, never()).retryRun(any(), any());
        verify(mapper).markWorkflowGenerated(9L, 1, "retry answer");
    }

    @Test
    void retryFailedWorkflowBindsNewExecutionEpoch() {
        KnowledgeChatMessage message = message("QUEUED", "FAILED", EXECUTION_ID.toString(), 1);
        message.setRetryCount(2);
        UUID newExecution = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(mapper.getTaskById(9L)).thenReturn(message);
        when(client.retryRun(RUN_ID, "assistant:9:retry:2")).thenReturn(accepted(2, newExecution));

        service.retry(9L);

        verify(mapper).bindWorkflowRetry(9L, RUN_ID.toString(), newExecution.toString(), 2);
    }

    private KnowledgeChatMessage message(String taskStatus, String workflowStatus, String executionId, Integer epoch) {
        KnowledgeChatMessage value = new KnowledgeChatMessage();
        value.setId(9L);
        value.setUserId(7L);
        value.setSessionId(8L);
        value.setSourceMessageId(6L);
        value.setTaskStatus(taskStatus);
        value.setWorkflowRunId(workflowStatus == null ? null : RUN_ID.toString());
        value.setWorkflowExecutionId(executionId);
        value.setWorkflowExecutionEpoch(epoch);
        value.setWorkflowStatus(workflowStatus);
        return value;
    }

    private WorkflowRunAccepted accepted(int epoch, UUID executionId) {
        return new WorkflowRunAccepted("1.0", RUN_ID, executionId, epoch,
                WorkflowRunStatus.QUEUED, Instant.parse("2026-07-22T00:00:00Z"));
    }

    private WorkflowRunStatusResponse status(WorkflowRunStatus status, UUID executionId, int epoch) {
        return new WorkflowRunStatusResponse("1.0", RUN_ID, executionId, epoch, status,
                Instant.parse("2026-07-22T00:00:00Z"), Instant.parse("2026-07-22T00:00:01Z"));
    }

    private WorkflowResult result(WorkflowRunStatus status, UUID executionId, int epoch) {
        String requestHash = "a".repeat(64);
        String snapshotHash = "b".repeat(64);
        return new WorkflowResult("1.0", RUN_ID, executionId, epoch, status, requestHash,
                snapshotHash, "c".repeat(64), Map.of("kind", "context"),
                new SnapshotDraft(2, requestHash, snapshotHash, List.of("query"), List.of(), List.of(), "context"),
                new RetrievalTrace(1, List.of()), List.of(5L), status == WorkflowRunStatus.DEGRADED ? "partial" : null);
    }

    private WorkflowRunCreateRequest createRequest() {
        return new WorkflowRunCreateRequest("1.0", WorkflowType.ASSISTANT, WorkflowVersion.V2,
                7, 8, 6, 9, "question", List.of(), Map.of("userId", 7),
                new KnowledgeScopeDecision(true, List.of(5L), List.of()), "assistant-workflow-v1",
                "a".repeat(64), new RunBudget(6, 3, 8, 120_000));
    }
}
