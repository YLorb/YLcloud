package com.ylcloud.service;

import com.ylcloud.service.space.SpacePermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.entity.RagTrustedAnswer;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.service.memory.UserMemoryExtractionService;
import com.ylcloud.service.rag.RagEvidenceService;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.workflow.client.WorkflowMessageLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeChatQueryServiceTest {
    @Test void queuedApiTaskRestoresServerAttributionAndRejectsRevocationBeforeModelWork() throws Exception {
        var task=readyTask(null,3L); task.setMeteringApiKeyId(23L); task.setMeteringRequestId(java.util.UUID.randomUUID().toString());
        var keys=mock(UserApiKeyService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionApiKeys",keys);
        doThrow(new BaseException(403,"revoked key")).when(keys).requireOwnedActivePrincipal(7L,23L);
        service.execute(11L);
        verifyNoInteractions(ragQueryService,contextService);
        verify(messageMapper).markChatFailedFenced(eq(11L),eq(3L),isNull(),eq("revoked key"),any());
        assertNull(com.ylcloud.service.metering.ApiUsageContext.current());
    }
    @Test void apiTaskUsesActualMeteringAndNeverChargesContextEstimateAgain() throws Exception {
        var task=readyTask(null,3L); task.setMeteringApiKeyId(23L); task.setMeteringRequestId(java.util.UUID.randomUUID().toString());
        trustedAnswer(); var keys=mock(UserApiKeyService.class); var quotas=mock(QuotaService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionApiKeys",keys);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"quotaService",quotas);
        var principal=new com.ylcloud.security.ApiKeyPrincipal(23L,7L,"test","NONE",null,java.util.Set.of("KNOWLEDGE_AGENT"),java.util.Set.of(5L));
        when(keys.requireOwnedActivePrincipal(7L,23L)).thenReturn(principal);
        when(ragQueryService.query(any(),eq(7L))).thenAnswer(inv->{
            var scope=com.ylcloud.service.metering.ApiUsageContext.current();
            assertEquals(23,scope.keyId); assertEquals(11,scope.messageId); assertTrue(scope.agent());
            assertEquals(task.getMeteringRequestId(),scope.requestId);
            var result=new KnowledgeRagQueryVO(); result.setAnswerId("trusted-1"); result.setCitations(List.of()); return result;
        });
        service.execute(11L);
        verify(keys).requireSpace(principal,5L,true);
        verify(quotas,never()).consumeModelTokens(anyLong(),anyLong());
        assertNull(com.ylcloud.service.metering.ApiUsageContext.current());
    }
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
    void executeCommitsCanonicalServerAnswerAndTrustBindingAtomically() throws Exception {
        readyTask(null,3L);
        RagEvidenceService evidence=trustedAnswer();
        when(messageMapper.markChatTrustedSuccess(eq(11L),eq(3L),isNull(),eq("server verified answer"),eq("[]"),eq("trusted-1"),any())).thenReturn(1);

        service.execute(11L);

        var order=inOrder(evidence,messageMapper);
        order.verify(evidence).readAnswer("trusted-1",7L);
        order.verify(evidence).validateCitations(7L,List.of(),true);
        order.verify(messageMapper).markChatTrustedSuccess(eq(11L),eq(3L),isNull(),eq("server verified answer"),eq("[]"),eq("trusted-1"),any());
        verify(messageMapper,never()).markSuccess(anyLong(),anyString(),anyString());
        verify(messageMapper,never()).bindTrustedAnswer(anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void revokedEvidenceFailsBeforePublishingSuccess() throws Exception {
        readyTask(null,3L);
        RagEvidenceService evidence=trustedAnswer();
        doThrow(new BaseException("permission revoked")).when(evidence).validateCitations(7L,List.of(),true);

        service.execute(11L);

        verify(messageMapper,never()).markChatTrustedSuccess(anyLong(),anyLong(),any(),anyString(),anyString(),anyString(),any());
        verify(messageMapper).markChatFailedFenced(eq(11L),eq(3L),isNull(),eq("permission revoked"),any());
    }

    @Test
    void answerWithoutServerIdentityCannotBecomeSuccessfulMessage() throws Exception {
        readyTask(null,3L); trustedAnswer();
        KnowledgeRagQueryVO result=new KnowledgeRagQueryVO(); result.setAnswer("unverified answer"); result.setCitations(List.of());
        when(ragQueryService.query(any(),eq(7L))).thenReturn(result);

        service.execute(11L);

        verify(messageMapper,never()).markChatTrustedSuccess(anyLong(),anyLong(),any(),anyString(),anyString(),anyString(),any());
        verify(messageMapper).markChatFailedFenced(eq(11L),eq(3L),isNull(),eq("可信回答关联失败"),any());
    }

    @Test
    void lateLegacyResultCannotPublishForRetriedMessage() throws Exception {
        readyTask(null,3L);
        trustedAnswer();
        WebhookEventService events=mock(WebhookEventService.class); service.setWebhookEventService(events);
        when(messageMapper.markChatTrustedSuccess(eq(11L),eq(3L),isNull(),anyString(),anyString(),anyString(),any())).thenReturn(0);

        service.execute(11L);

        verify(messageMapper).claimQueuedFenced(11L,3L);
        verifyNoInteractions(events);
        verify(messageMapper,never()).markChatFailedFenced(anyLong(),anyLong(),any(),anyString(),any());
    }

    @Test
    void lateLegacyFailureCannotPublishForRetriedMessage() throws Exception {
        readyTask(null,3L);
        when(ragQueryService.query(any(),eq(7L))).thenThrow(new BaseException("old attempt failed"));
        WebhookEventService events=mock(WebhookEventService.class); service.setWebhookEventService(events);
        when(messageMapper.markChatFailedFenced(eq(11L),eq(3L),isNull(),anyString(),any())).thenReturn(0);

        service.execute(11L);

        verify(messageMapper).markChatFailedFenced(eq(11L),eq(3L),isNull(),eq("old attempt failed"),any());
        verifyNoInteractions(events);
    }

    @Test
    void boundMqTaskCannotBeClaimedByLegacyWorker() throws Exception {
        readyTask(30L,3L);

        service.execute(11L);

        verify(messageMapper,never()).claimQueuedFenced(anyLong(),anyLong());
        verifyNoInteractions(ragQueryService);
    }

    @Test
    void asyncSuccessWritesTrustedAnswerUnderSameTaskFence() throws Exception {
        readyTask(30L,3L); trustedAnswer();
        when(messageMapper.markChatTrustedSuccess(eq(11L),eq(3L),eq(30L),anyString(),anyString(),anyString(),any())).thenReturn(1);

        assertEquals("SUCCESS",service.executeAsync(11L,30L,3L,false,mock(TaskExecutionContext.class)).get("status"));

        verify(messageMapper).markChatTrustedSuccess(eq(11L),eq(3L),eq(30L),eq("server verified answer"),eq("[]"),eq("trusted-1"),any());
        verify(messageMapper,never()).bindTrustedAnswer(anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void lostAsyncFenceDoesNotOverwriteNewAttemptWithFailure() throws Exception {
        readyTask(30L,3L); trustedAnswer();

        assertThrows(StaleTaskException.class,()->service.executeAsync(11L,30L,3L,false,mock(TaskExecutionContext.class)));

        verify(messageMapper,never()).markChatFailedFenced(anyLong(),anyLong(),any(),anyString(),any());
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
    void reconcilesLegacyTerminalEventWithoutReexposingStoredAnswer() throws Exception {
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
                eq(null),eq(5L),eq(Map.of("messageId",11L,"sessionId",3L,"status","COMPLETED")),eq(Map.of()));
    }

    @Test
    void trustedCompletionWebhookContainsOnlyResolvableAnswerId() throws Exception {
        KnowledgeChatMessage task=readyTask(null,3L); trustedAnswer();
        WebhookEventService events=mock(WebhookEventService.class); service.setWebhookEventService(events);
        when(messageMapper.markChatTrustedSuccess(eq(11L),eq(3L),isNull(),anyString(),anyString(),anyString(),any())).thenReturn(1);

        service.execute(11L);

        verify(events).publish(eq(7L),eq("AGENT_TASK_COMPLETED"),eq("AGENT_TASK"),eq("11"),eq(1L),
                isNull(),eq(5L),eq(Map.of("messageId",11L,"sessionId",3L,"status","COMPLETED","answerId","trusted-1")),eq(Map.of()));
        assertEquals("trusted-1",task.getTrustedAnswerId());
    }

    private KnowledgeChatMessage readyTask(Long asyncTaskId,long version) throws Exception {
        KnowledgeChatMessage task=new KnowledgeChatMessage();
        task.setId(11L); task.setSessionId(3L); task.setUserId(7L); task.setRole("assistant"); task.setStatus(1);
        task.setTaskStatus("QUEUED"); task.setAsyncVersion(version); task.setAsyncTaskId(asyncTaskId);
        KnowledgeChatQueryCreateDTO request=request(); request.setApiKeyId(19L);
        task.setRequestJson(new ObjectMapper().writeValueAsString(request));
        when(messageMapper.getTaskById(11L)).thenReturn(task);
        when(messageMapper.claimQueuedFenced(11L,version)).thenReturn(1);
        when(messageMapper.claimChatAsync(eq(11L),eq(version),eq(asyncTaskId),any())).thenReturn(1);
        when(contextService.resolve(task)).thenReturn(new ConversationContextSnapshot(1,3L,7L,11L,10L,List.of(),List.of(),0,0,1,0,List.of(),"test",java.time.LocalDateTime.now()));
        KnowledgeRagQueryVO result=new KnowledgeRagQueryVO(); result.setAnswer("untrusted response text"); result.setCitations(List.of()); result.setAnswerId("trusted-1");
        when(ragQueryService.query(any(),eq(7L))).thenReturn(result);
        return task;
    }

    private RagEvidenceService trustedAnswer() {
        RagEvidenceService evidence=mock(RagEvidenceService.class); service.setEvidenceService(evidence);
        RagTrustedAnswer answer=new RagTrustedAnswer(); answer.setId("trusted-1"); answer.setUserId(7L);
        answer.setContent("server verified answer"); answer.setCitationsJson("[]");
        when(evidence.readAnswer("trusted-1",7L)).thenReturn(answer);
        return evidence;
    }

    private KnowledgeChatQueryCreateDTO request() { KnowledgeChatQueryCreateDTO dto = new KnowledgeChatQueryCreateDTO(); dto.setQuestion("question"); dto.setSpaceIds(List.of(5L)); dto.setRetrievalMode("balanced"); return dto; }
}
