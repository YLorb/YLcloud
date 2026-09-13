package com.ylcloud.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.UserMemoryExtractionTask;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryMqMigrationTest {
    @Test
    void enqueueCreatesIdOnlyMemoryTaskWithoutUsingLocalExecutor() {
        UserMemoryExtractionTaskMapper tasks=mock(UserMemoryExtractionTaskMapper.class);
        UserMemoryExtractionTask extraction=extraction();
        when(tasks.getByAssistantMessageId(20L)).thenReturn(extraction);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.createTask(any())).thenReturn(unified(30L));
        Executor executor=mock(Executor.class);
        UserMemoryService memories=mock(UserMemoryService.class);
        when(memories.enabled(7L)).thenReturn(true);
        UserMemoryExtractionService service=extractionService(tasks,mock(KnowledgeChatMessageMapper.class),
                memories,mock(RagModelClient.class),executor);
        service.setAsyncTaskInfrastructure(memoryProperties(),center);
        KnowledgeChatMessage assistant=new KnowledgeChatMessage();
        assistant.setId(20L); assistant.setUserId(7L); assistant.setSessionId(8L); assistant.setSourceMessageId(10L);

        service.enqueue(assistant);

        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo("MEMORY_EXTRACT");
        assertThat(command.getValue().resourceKey()).isEqualTo("user-memory:7:10");
        assertThat(command.getValue().payload()).isEqualTo(new DomainTaskPayload(11L));
        verify(tasks).bindAsyncTask(eq(11L),eq(1L),eq(30L),any());
        verifyNoInteractions(executor);
    }

    @Test
    void cancellationAfterModelResponseCannotPersistAProfile() throws Exception {
        UserMemoryExtractionTaskMapper tasks=mock(UserMemoryExtractionTaskMapper.class);
        when(tasks.getById(11L)).thenReturn(extraction());
        when(tasks.claimAsync(eq(11L),eq(30L),eq(1L),any())).thenReturn(1);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        when(messages.getOwned(10L,8L,7L)).thenReturn(source("只使用中文回答"));
        UserMemoryService memories=mock(UserMemoryService.class);
        when(memories.enabled(7L)).thenReturn(true);
        RagModelClient model=mock(RagModelClient.class);
        RagGenerateResponse response=new RagGenerateResponse();
        response.setText("[{\"type\":\"PREFERENCE\",\"key\":\"language\",\"content\":\"偏好中文\",\"confidence\":0.9}]");
        when(model.generate(any())).thenReturn(response);
        UserMemoryExtractionService service=extractionService(tasks,messages,memories,model,Runnable::run);
        TaskExecutionContext context=mock(TaskExecutionContext.class);
        doNothing().doThrow(new TaskCanceledException()).when(context).checkpoint();

        assertThrows(TaskCanceledException.class,() -> service.executeAsync(11L,30L,1L,context));

        verify(memories,never()).acceptFromTask(anyLong(),anyLong(),anyLong(),anyString(),any(),anyLong());
        verify(tasks).skipAsync(eq(11L),eq(30L),eq(1L),eq("Unified task canceled"),any());
    }

    @Test
    void modelFailureProducesOnlySafeRetrySummary() throws Exception {
        UserMemoryExtractionTaskMapper tasks=mock(UserMemoryExtractionTaskMapper.class);
        when(tasks.getById(11L)).thenReturn(extraction());
        when(tasks.claimAsync(eq(11L),eq(30L),eq(1L),any())).thenReturn(1);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        when(messages.getOwned(10L,8L,7L)).thenReturn(source("机密对话正文"));
        UserMemoryService memories=mock(UserMemoryService.class);
        when(memories.enabled(7L)).thenReturn(true);
        RagModelClient model=mock(RagModelClient.class);
        when(model.generate(any())).thenThrow(new IllegalStateException("机密对话正文 token=abcdef"));
        UserMemoryExtractionService service=extractionService(tasks,messages,memories,model,Runnable::run);

        RetryableTaskException error=assertThrows(RetryableTaskException.class,
                () -> service.executeAsync(11L,30L,1L,mock(TaskExecutionContext.class)));

        assertThat(error.getMessage()).doesNotContain("机密","token");
        verify(tasks).failAsync(eq(11L),eq(30L),eq(1L),eq("Memory processing is temporarily unavailable"),any());
    }

    @Test
    void sourceUpdateAfterModelResponseMakesExtractionStale() throws Exception {
        UserMemoryExtractionTaskMapper tasks=mock(UserMemoryExtractionTaskMapper.class);
        when(tasks.getById(11L)).thenReturn(extraction());
        when(tasks.claimAsync(eq(11L),eq(30L),eq(1L),any())).thenReturn(1);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        when(messages.getOwned(10L,8L,7L)).thenReturn(source("旧内容"),source("新内容"));
        UserMemoryService memories=mock(UserMemoryService.class);
        when(memories.enabled(7L)).thenReturn(true);
        RagModelClient model=mock(RagModelClient.class);
        RagGenerateResponse response=new RagGenerateResponse(); response.setText("[]");
        when(model.generate(any())).thenReturn(response);
        UserMemoryExtractionService service=extractionService(tasks,messages,memories,model,Runnable::run);

        assertThrows(StaleTaskException.class,
                () -> service.executeAsync(11L,30L,1L,mock(TaskExecutionContext.class)));

        verify(memories,never()).acceptFromTask(anyLong(),anyLong(),anyLong(),anyString(),any(),anyLong());
        verify(tasks).skipAsync(eq(11L),eq(30L),eq(1L),eq("Memory source is stale"),any());
    }

    @Test
    void acceptedCandidateCreatesProfileTaskWithNoMemoryContentInPayload() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        when(mapper.isEnabled(7L)).thenReturn(true);
        doAnswer(invocation -> { ((UserMemoryItem)invocation.getArgument(0)).setId(41L); return 1; }).when(mapper).insert(any());
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.createTask(any())).thenReturn(unified(50L));
        UserMemoryService service=memoryService(mapper,mock(UserMemoryVectorStoreService.class),center);

        UserMemoryItem item=service.acceptFromTask(7L,8L,10L,"来源正文",
                new UserMemoryCandidate("PREFERENCE","response.language","偏好中文",0.9,true),30L);

        assertThat(item.getOriginAsyncTaskId()).isEqualTo(30L);
        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo("MEMORY_PROFILE_BUILD");
        assertThat(command.getValue().payload()).isEqualTo(new DomainTaskPayload(41L));
        assertThat(command.getValue().payload().toString()).doesNotContain("偏好中文","来源正文");
    }

    @Test
    void profileStageCreatesVersionFencedVectorTask() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryItem item=candidate(); item.setProfileAsyncTaskId(50L); item.setOriginAsyncTaskId(30L);
        when(mapper.getById(41L)).thenReturn(item);
        when(mapper.bindVectorTask(eq(41L),eq(1L),eq(50L),eq(60L),any())).thenReturn(1);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.status(30L)).thenReturn("SUCCESS");
        when(center.createTask(any())).thenReturn(unified(60L));
        UserMemoryService service=memoryService(mapper,mock(UserMemoryVectorStoreService.class),center);

        service.executeProfileAsync(41L,50L,1L,mock(TaskExecutionContext.class));

        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo("MEMORY_VECTOR_INDEX");
        verify(mapper).bindVectorTask(eq(41L),eq(1L),eq(50L),eq(60L),any());
    }

    @Test
    void canceledExtractionOriginCannotFanOutVectorTask() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryItem item=candidate(); item.setProfileAsyncTaskId(50L); item.setOriginAsyncTaskId(30L);
        when(mapper.getById(41L)).thenReturn(item);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.status(30L)).thenReturn("CANCELED");
        UserMemoryService service=memoryService(mapper,mock(UserMemoryVectorStoreService.class),center);

        assertThrows(StaleTaskException.class,
                () -> service.executeProfileAsync(41L,50L,1L,mock(TaskExecutionContext.class)));

        verify(center,never()).createTask(any());
        verify(mapper,never()).bindVectorTask(anyLong(),anyLong(),anyLong(),anyLong(),any());
    }

    @Test
    void canceledVectorCallCannotActivateMemory() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryItem item=candidate(); item.setVectorAsyncTaskId(60L);
        when(mapper.getById(41L)).thenReturn(item);
        when(mapper.claimIndexAsync(eq(41L),eq(1L),eq(60L),any())).thenReturn(1);
        UserMemoryVectorStoreService vectors=mock(UserMemoryVectorStoreService.class);
        when(vectors.upsert(item)).thenReturn("point-41");
        UserMemoryService service=memoryService(mapper,vectors,mock(UnifiedTaskCenterService.class));
        TaskExecutionContext context=mock(TaskExecutionContext.class);
        doNothing().doThrow(new TaskCanceledException()).when(context).checkpoint();

        assertThrows(TaskCanceledException.class,() -> service.executeIndexAsync(41L,60L,1L,context));

        verify(mapper).cancelIndexAsync(eq(41L),eq(1L),eq(60L),any());
        verify(mapper,never()).activateAsync(anyLong(),anyLong(),anyLong(),anyString(),any());
    }

    @Test
    void staleVectorVersionNeverCallsQdrant() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryItem item=candidate(); item.setAsyncVersion(2L); item.setVectorAsyncTaskId(60L);
        when(mapper.getById(41L)).thenReturn(item);
        UserMemoryVectorStoreService vectors=mock(UserMemoryVectorStoreService.class);
        UserMemoryService service=memoryService(mapper,vectors,mock(UnifiedTaskCenterService.class));

        assertThrows(StaleTaskException.class,() -> service.executeIndexAsync(41L,60L,1L,mock(TaskExecutionContext.class)));

        verifyNoInteractions(vectors);
    }

    private UserMemoryExtractionService extractionService(UserMemoryExtractionTaskMapper tasks,
                                                            KnowledgeChatMessageMapper messages,UserMemoryService memories,
                                                            RagModelClient model,Executor executor) {
        return new UserMemoryExtractionService(tasks,messages,memories,model,new RagProperties(),new ObjectMapper(),executor);
    }

    private UserMemoryService memoryService(UserMemoryItemMapper mapper,UserMemoryVectorStoreService vectors,
                                            UnifiedTaskCenterService center) {
        UserMemoryService service=new UserMemoryService(mapper,vectors,new RagProperties());
        service.setAsyncTaskInfrastructure(memoryProperties(),center);
        return service;
    }

    private UserMemoryExtractionTask extraction() {
        UserMemoryExtractionTask task=new UserMemoryExtractionTask();
        task.setId(11L); task.setAssistantMessageId(20L); task.setUserId(7L); task.setSessionId(8L);
        task.setSourceMessageId(10L); task.setTaskStatus("PENDING"); task.setAsyncTaskId(30L); task.setResourceVersion(1L);
        return task;
    }

    private KnowledgeChatMessage source(String content) {
        KnowledgeChatMessage message=new KnowledgeChatMessage();
        message.setId(10L); message.setUserId(7L); message.setSessionId(8L); message.setRole("user"); message.setContent(content);
        return message;
    }

    private UserMemoryItem candidate() {
        UserMemoryItem item=new UserMemoryItem();
        item.setId(41L); item.setUserId(7L); item.setNormalizedKey("response.language"); item.setContent("偏好中文");
        item.setVersion(1); item.setAsyncVersion(1L); item.setMemoryStatus("CANDIDATE"); item.setEmbeddingStatus("PENDING"); item.setStatus(1);
        return item;
    }

    private UnifiedAsyncTask unified(Long id) { UnifiedAsyncTask task=new UnifiedAsyncTask(); task.setId(id); return task; }
    private AsyncMqProperties memoryProperties() { AsyncMqProperties value=new AsyncMqProperties(); value.setEnabled(true); value.setMemory(true); return value; }

}
