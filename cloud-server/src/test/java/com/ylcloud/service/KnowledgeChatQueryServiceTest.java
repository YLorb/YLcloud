package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.service.memory.UserMemoryExtractionService;
import com.ylcloud.workflow.client.WorkflowMessageLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatQueryServiceTest {
    private final KnowledgeChatSessionMapper sessionMapper = mock(KnowledgeChatSessionMapper.class);
    private final KnowledgeChatMessageMapper messageMapper = mock(KnowledgeChatMessageMapper.class);
    private final SpacePermissionService permissionService = mock(SpacePermissionService.class);
    private final KnowledgeRagQueryService ragQueryService = mock(KnowledgeRagQueryService.class);
    private final ConversationContextService contextService = mock(ConversationContextService.class);
    private final KnowledgeChatQueryService service = new KnowledgeChatQueryService(
            sessionMapper,messageMapper,permissionService,ragQueryService,contextService,new ObjectMapper(),Runnable::run);

    @Test
    void submitPersistsUserAndRecoverableAssistantMessages() {
        KnowledgeChatSession session = new KnowledgeChatSession(); session.setId(3L); session.setUserId(7L);
        when(sessionMapper.getActiveForUpdate(3L,7L)).thenReturn(session);
        when(sessionMapper.reserveSequences(eq(3L),eq(7L),eq(2),any())).thenReturn(1);
        when(messageMapper.insert(any())).thenAnswer(invocation -> { KnowledgeChatMessage message = invocation.getArgument(0); message.setId("user".equals(message.getRole()) ? 10L : 11L); return 1; });

        var result = service.submit(3L,7L,request(),"request-1");

        ArgumentCaptor<KnowledgeChatMessage> messages = ArgumentCaptor.forClass(KnowledgeChatMessage.class);
        verify(messageMapper,times(2)).insert(messages.capture());
        assertEquals("user",messages.getAllValues().get(0).getRole());
        assertEquals("QUEUED",messages.getAllValues().get(1).getTaskStatus());
        assertEquals("request-1",messages.getAllValues().get(1).getRequestKey());
        assertEquals(11L,result.getId());
    }

    @Test
    void executeStoresAnswerAndCitations() throws Exception {
        KnowledgeChatMessage task = new KnowledgeChatMessage();
        task.setId(11L); task.setUserId(7L); task.setTaskStatus("RUNNING");
        task.setRequestJson(new ObjectMapper().writeValueAsString(request()));
        when(messageMapper.claimQueued(11L)).thenReturn(1);
        when(messageMapper.getTaskById(11L)).thenReturn(task);
        when(contextService.resolve(task)).thenReturn(new ConversationContextSnapshot(1,3L,7L,11L,10L,List.of(),List.of(),0,0,1,0,List.of(),"test",java.time.LocalDateTime.now()));
        KnowledgeRagQueryVO result = new KnowledgeRagQueryVO(); result.setAnswer("answer"); result.setCitations(List.of());
        when(ragQueryService.query(any(),eq(7L))).thenReturn(result);

        service.execute(11L);

        verify(messageMapper).markSuccess(11L,"answer","[]");
    }

    @Test
    void retryWithBoundWorkflowDispatchesLifecycleInsteadOfLegacyRag() {
        WorkflowMessageLifecycleService lifecycle = mock(WorkflowMessageLifecycleService.class);
        KnowledgeChatQueryService workflowService = new KnowledgeChatQueryService(sessionMapper,messageMapper,
                permissionService,ragQueryService,contextService,mock(UserMemoryExtractionService.class),
                new ObjectMapper(),Runnable::run,lifecycle);
        KnowledgeChatSession session = new KnowledgeChatSession(); session.setId(3L);
        KnowledgeChatMessage failed = new KnowledgeChatMessage();
        failed.setId(11L); failed.setSessionId(3L); failed.setUserId(7L); failed.setRole("assistant");
        failed.setTaskStatus("FAILED"); failed.setWorkflowRunId("11111111-1111-4111-8111-111111111111");
        when(sessionMapper.getActive(3L,7L)).thenReturn(session);
        when(messageMapper.getOwned(11L,3L,7L)).thenReturn(failed);
        when(lifecycle.isEnabled()).thenReturn(true);
        when(messageMapper.prepareWorkflowRetry(11L)).thenReturn(1);

        workflowService.retry(3L,11L,7L);

        verify(messageMapper).prepareWorkflowRetry(11L);
        verify(lifecycle).retry(11L);
        verify(messageMapper,never()).retryFailed(11L);
    }

    @Test
    void reconcilesMissingTerminalAgentWebhookEvent() throws Exception {
        WebhookEventService events = mock(WebhookEventService.class);
        KnowledgeChatMessage message = new KnowledgeChatMessage();
        message.setId(11L); message.setSessionId(3L); message.setUserId(7L);
        message.setTaskStatus("SUCCESS"); message.setContent("answer"); message.setRetryCount(1);
        KnowledgeChatQueryCreateDTO request = request();
        request.setApiKeyId(19L);
        message.setRequestJson(new ObjectMapper().writeValueAsString(request));
        when(messageMapper.listMissingAgentWebhookEvents(100)).thenReturn(List.of(message));
        service.setWebhookEventService(events);

        service.reconcileAgentWebhookEvents();

        verify(events).publish(eq(7L),eq("AGENT_TASK_COMPLETED"),eq("AGENT_TASK"),eq("11"),eq(2L),
                eq(null),eq(5L),any(),eq(java.util.Map.of("answer","answer")));
    }

    private KnowledgeChatQueryCreateDTO request() { KnowledgeChatQueryCreateDTO dto = new KnowledgeChatQueryCreateDTO(); dto.setQuestion("question"); dto.setSpaceIds(List.of(5L)); dto.setRetrievalMode("balanced"); return dto; }
}
