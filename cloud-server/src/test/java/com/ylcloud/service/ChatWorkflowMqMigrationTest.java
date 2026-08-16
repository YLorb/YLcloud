package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskCanceledException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowMqMigrationTest {
    @Test
    void submitCreatesIdOnlyChatTaskAndOutboxPathWithoutLocalExecutor() {
        KnowledgeChatSessionMapper sessions=mock(KnowledgeChatSessionMapper.class);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        Executor executor=mock(Executor.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.createTask(any())).thenReturn(unified(30L));
        when(messages.bindAsyncTask(eq(11L),eq(1L),eq(30L),eq("CHAT_QUERY"),any())).thenReturn(1);
        KnowledgeChatSession session=new KnowledgeChatSession(); session.setId(3L); session.setUserId(7L);
        when(sessions.getActiveForUpdate(3L,7L)).thenReturn(session);
        when(sessions.reserveSequences(eq(3L),eq(7L),eq(2),any())).thenReturn(1);
        when(messages.insert(any())).thenAnswer(invocation -> {
            KnowledgeChatMessage message=invocation.getArgument(0);
            message.setId("user".equals(message.getRole()) ? 10L : 11L);
            return 1;
        });
        KnowledgeChatQueryService service=service(sessions,messages,executor);
        service.setUnifiedTaskCenter(center,chatProperties());

        service.submit(3L,7L,request(),"idem-1");

        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertThat(command.getValue().taskKey()).isEqualTo("chat-message:11:CHAT_QUERY:1");
        assertThat(command.getValue().payload()).isEqualTo(new DomainTaskPayload(11L));
        assertThat(command.getValue().payload().toString()).doesNotContain("question");
        verifyNoInteractions(executor);
    }

    @Test
    void retryAdvancesResourceVersionAndUsesNewStableTaskKey() {
        KnowledgeChatSessionMapper sessions=mock(KnowledgeChatSessionMapper.class);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        KnowledgeChatSession session=new KnowledgeChatSession(); session.setId(3L);
        KnowledgeChatMessage failed=assistant("FAILED"); failed.setAsyncVersion(1L);
        when(sessions.getActive(3L,7L)).thenReturn(session);
        when(messages.getOwned(11L,3L,7L)).thenReturn(failed);
        when(messages.retryFailed(11L)).thenReturn(1);
        when(center.createTask(any())).thenReturn(unified(31L));
        when(messages.bindAsyncTask(eq(11L),eq(2L),eq(31L),eq("CHAT_QUERY"),any())).thenReturn(1);
        KnowledgeChatQueryService service=service(sessions,messages,Runnable::run);
        service.setUnifiedTaskCenter(center,chatProperties());

        service.retry(3L,11L,7L);

        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertThat(command.getValue().taskKey()).isEqualTo("chat-message:11:CHAT_QUERY:2");
        assertThat(command.getValue().resourceVersion()).isEqualTo(2L);
    }

    @Test
    void cancelRequestsCentralCancellationAndAdvancesDomainFence() {
        KnowledgeChatSessionMapper sessions=mock(KnowledgeChatSessionMapper.class);
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        KnowledgeChatSession session=new KnowledgeChatSession(); session.setId(3L);
        KnowledgeChatMessage queued=assistant("QUEUED"); queued.setAsyncTaskId(30L); queued.setAsyncVersion(1L);
        when(sessions.getActive(3L,7L)).thenReturn(session);
        when(messages.getOwned(11L,3L,7L)).thenReturn(queued);
        KnowledgeChatQueryService service=service(sessions,messages,Runnable::run);
        service.setUnifiedTaskCenter(center,chatProperties());

        service.cancel(3L,11L,7L);

        verify(center).cancel(30L,7L,"用户取消回答");
        verify(messages).cancelChatAsync(eq(11L),eq(1L),eq(30L),any(LocalDateTime.class));
    }

    @Test
    void cancellationAfterModelResponseCannotWriteLateAnswer() throws Exception {
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        KnowledgeRagQueryService rag=mock(KnowledgeRagQueryService.class);
        ConversationContextService contexts=mock(ConversationContextService.class);
        KnowledgeChatMessage task=assistant("QUEUED"); task.setAsyncTaskId(30L); task.setAsyncVersion(1L);
        task.setRequestJson(new ObjectMapper().writeValueAsString(request()));
        when(messages.getTaskById(11L)).thenReturn(task);
        when(messages.claimChatAsync(eq(11L),eq(1L),eq(30L),any())).thenReturn(1);
        when(contexts.resolve(task)).thenReturn(snapshot());
        KnowledgeRagQueryVO response=new KnowledgeRagQueryVO(); response.setAnswer("late secret answer"); response.setCitations(List.of());
        when(rag.query(any(),eq(7L))).thenReturn(response);
        TaskExecutionContext context=mock(TaskExecutionContext.class);
        doNothing().doNothing().doThrow(new TaskCanceledException()).when(context).checkpoint();
        KnowledgeChatQueryService service=new KnowledgeChatQueryService(mock(KnowledgeChatSessionMapper.class),messages,
                mock(SpacePermissionService.class),rag,contexts,new ObjectMapper(),Runnable::run);

        assertThrows(TaskCanceledException.class,() -> service.executeAsync(11L,30L,1L,false,context));

        verify(messages,never()).markChatSuccessAsync(anyLong(),anyLong(),anyLong(),anyString(),anyString(),any());
    }

    @Test
    void staleVersionNeverCallsRagOrModel() {
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        KnowledgeChatMessage task=assistant("QUEUED"); task.setAsyncTaskId(30L); task.setAsyncVersion(2L);
        when(messages.getTaskById(11L)).thenReturn(task);
        KnowledgeRagQueryService rag=mock(KnowledgeRagQueryService.class);
        KnowledgeChatQueryService service=new KnowledgeChatQueryService(mock(KnowledgeChatSessionMapper.class),messages,
                mock(SpacePermissionService.class),rag,mock(ConversationContextService.class),new ObjectMapper(),Runnable::run);

        assertThrows(StaleTaskException.class,
                () -> service.executeAsync(11L,30L,1L,false,mock(TaskExecutionContext.class)));

        verifyNoInteractions(rag);
    }

    @Test
    void recoveryOnlyRegistersQueuedTasksWhenChatFlagIsEnabled() {
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        KnowledgeChatMessage queued=assistant("QUEUED"); queued.setAsyncVersion(1L);
        when(messages.listQueued(100)).thenReturn(List.of(queued));
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.createTask(any())).thenReturn(unified(30L));
        when(messages.bindAsyncTask(eq(11L),eq(1L),eq(30L),eq("CHAT_QUERY"),any())).thenReturn(1);
        Executor executor=mock(Executor.class);
        KnowledgeChatQueryService service=service(mock(KnowledgeChatSessionMapper.class),messages,executor);
        service.setUnifiedTaskCenter(center,chatProperties());

        service.recoverPendingQueries();

        verify(center).createTask(any());
        verifyNoInteractions(executor);
        verify(messages,never()).requeueStale(any());
    }

    private KnowledgeChatQueryService service(KnowledgeChatSessionMapper sessions,KnowledgeChatMessageMapper messages,Executor executor) {
        return new KnowledgeChatQueryService(sessions,messages,mock(SpacePermissionService.class),
                mock(KnowledgeRagQueryService.class),mock(ConversationContextService.class),new ObjectMapper(),executor);
    }

    private KnowledgeChatMessage assistant(String status) {
        KnowledgeChatMessage message=new KnowledgeChatMessage();
        message.setId(11L); message.setSessionId(3L); message.setUserId(7L); message.setRole("assistant");
        message.setTaskStatus(status); message.setStatus(1); message.setAsyncVersion(1L);
        return message;
    }

    private KnowledgeChatQueryCreateDTO request() {
        KnowledgeChatQueryCreateDTO dto=new KnowledgeChatQueryCreateDTO();
        dto.setQuestion("question"); dto.setSpaceIds(List.of(5L)); dto.setRetrievalMode("balanced");
        return dto;
    }

    private ConversationContextSnapshot snapshot() {
        return new ConversationContextSnapshot(1,3L,7L,11L,10L,List.of(),List.of(),0,0,1,0,List.of(),"hash",LocalDateTime.now());
    }

    private UnifiedAsyncTask unified(Long id) {
        UnifiedAsyncTask task=new UnifiedAsyncTask(); task.setId(id); task.setResourceVersion(1L); return task;
    }

    private AsyncMqProperties chatProperties() {
        AsyncMqProperties value=new AsyncMqProperties(); value.setEnabled(true); value.setChat(true); return value;
    }
}
