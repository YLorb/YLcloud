package com.ylcloud.async.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AsyncDemoCreateDTO;
import com.ylcloud.async.TaskDispatchEnvelope;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.AccessControlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedTaskCenterServiceTest {
    private final UnifiedAsyncTaskMapper mapper = mock(UnifiedAsyncTaskMapper.class);
    private final TaskAuthorizationService authorization = mock(TaskAuthorizationService.class);
    private final AccessControlMapper audit = mock(AccessControlMapper.class);
    private final AsyncMqProperties properties = new AsyncMqProperties();
    private final UnifiedTaskCenterService service =
            new UnifiedTaskCenterService(mapper,authorization,audit,properties,new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    void defaults() {
        properties.setMaxAttempts(4);
        properties.setLeaseSeconds(120);
        properties.setRetrySeconds(60);
        properties.setRetentionDays(3);
    }

    @Test
    void createsTaskAndOutboxInOneCallWithStableKey() {
        doAnswer(invocation -> {
            UnifiedAsyncTask task = invocation.getArgument(0);
            task.setId(9L);
            return 1;
        }).when(mapper).insertTask(any());
        AsyncDemoCreateDTO dto = demo("SUCCESS");

        UnifiedAsyncTask task = service.createDemo(dto,7L);

        assertEquals("PENDING_PUBLISH",task.getStatus());
        assertTrue(task.getTaskKey().startsWith("async-demo:7:"));
        verify(mapper).insertOutbox(anyString(),eq(9L),eq(0),anyString(),eq("task.maintenance"),
                anyString(),any(),any());
    }

    @Test
    void createsCleanupDomainTaskWithIdOnlyPayload() {
        doAnswer(invocation -> { UnifiedAsyncTask task=invocation.getArgument(0); task.setId(12L); return 1; })
                .when(mapper).insertTask(any());

        UnifiedAsyncTask task=service.createTask(new TaskCreateCommand(
                "cleanup:12:1","cleanup","PHYSICAL_FILE_CLEANUP",new DomainTaskPayload(12L),
                null,null,"physical-file:file-1",1));

        assertEquals("cleanup",task.getTaskDomain());
        assertEquals("{\"resourceId\":12}",task.getPayloadJson());
        verify(mapper).insertOutbox(anyString(),eq(12L),eq(0),anyString(),eq("task.cleanup"),anyString(),any(),any());
    }

    @Test
    void claimCasIncrementsOnlyWhenMapperWinsAndCreatesAttempt() {
        UnifiedAsyncTask claimed = task("PENDING",0);
        claimed.setAttemptVersion(1);
        when(mapper.claimTask(eq(5L),eq(0),anyString(),eq("worker"),any(),any())).thenReturn(1);
        when(mapper.getById(5L)).thenReturn(claimed);
        when(mapper.latestResourceVersion("resource:5")).thenReturn(1L);
        TaskDispatchEnvelope envelope = envelope(0);

        UnifiedAsyncTask result = service.claim(envelope,"worker","lease");

        assertNotNull(result);
        verify(mapper).insertAttempt(eq(5L),eq(1),eq("INITIAL"),eq("worker"),eq("lease"),
                isNull(),isNull(),any());
    }

    @Test
    void duplicateOrWrongVersionCannotCreateAttempt() {
        when(mapper.claimTask(anyLong(),anyInt(),anyString(),anyString(),any(),any())).thenReturn(0);
        assertNull(service.claim(envelope(0),"worker","lease"));
        verify(mapper,never()).insertAttempt(anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),any(),any());
    }

    @Test
    void retryableFailureSchedulesNewMessageUntilMaximumAttempt() {
        UnifiedAsyncTask task = task("RUNNING",1);
        when(mapper.markFailure(anyLong(),anyInt(),anyString(),eq("RETRY_WAIT"),any(),eq("AUTO_RETRY"),
                anyString(),anyString(),anyString(),isNull(),any())).thenReturn(1);
        UnifiedAsyncTask updated = task("RETRY_WAIT",1);
        when(mapper.getById(5L)).thenReturn(updated);

        service.fail(task,"lease",new TaskFailure("TRANSIENT","TEMP","safe",true,false));

        verify(mapper).finishAttempt(eq(5L),eq(1),eq("lease"),eq("RETRY_WAIT"),anyString(),anyString(),
                anyString(),eq(true),any(),any());
        verify(mapper).insertOutbox(anyString(),eq(5L),eq(1),anyString(),anyString(),anyString(),any(),any());
    }

    @Test
    void fourthFailureIsTerminalAndDoesNotEnqueue() {
        UnifiedAsyncTask task = task("RUNNING",4);
        when(mapper.markFailure(anyLong(),anyInt(),anyString(),eq("FAILED"),isNull(),anyString(),
                anyString(),anyString(),anyString(),any(),any())).thenReturn(1);

        service.fail(task,"lease",new TaskFailure("TRANSIENT","TEMP","safe",true,false));

        verify(mapper,never()).insertOutbox(anyString(),anyLong(),anyInt(),anyString(),anyString(),anyString(),any(),any());
    }

    @Test
    void staleWorkerCannotLateWriteSuccess() {
        UnifiedAsyncTask task = task("RUNNING",2);
        when(mapper.markSuccess(anyLong(),anyInt(),anyString(),anyString(),any(),any())).thenReturn(0);
        assertFalse(service.complete(task,"old-lease", Map.of("ok",true)));
        verify(mapper,never()).finishAttempt(anyLong(),anyInt(),anyString(),eq("SUCCESS"),
                any(),any(),any(),any(),any(),any());
    }

    private AsyncDemoCreateDTO demo(String mode) {
        AsyncDemoCreateDTO dto = new AsyncDemoCreateDTO();
        dto.setText("hello");
        dto.setDelayMs(0);
        dto.setMode(mode);
        dto.setIdempotencyKey("same");
        return dto;
    }

    private UnifiedAsyncTask task(String status, int attempt) {
        UnifiedAsyncTask task = new UnifiedAsyncTask();
        task.setId(5L);
        task.setTaskDomain("maintenance");
        task.setTaskType("ASYNC_DEMO");
        task.setTaskKey("key");
        task.setResourceKey("resource:5");
        task.setResourceVersion(1L);
        task.setStatus(status);
        task.setAttemptVersion(attempt);
        task.setNextTriggerType("INITIAL");
        task.setMaxAttempts(4);
        task.setPayloadJson("{}");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    private TaskDispatchEnvelope envelope(int expected) {
        return new TaskDispatchEnvelope(1,"message","TASK_DISPATCH",5L,"maintenance","ASYNC_DEMO",
                expected,"resource:5",1L, Instant.now(),"trace");
    }
}
