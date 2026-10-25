package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.entity.RagTrustedAnswer;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.service.rag.RagEvidenceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class ConversationContextServiceTest {
    private final KnowledgeChatSessionMapper sessionMapper = mock(KnowledgeChatSessionMapper.class);
    private final KnowledgeChatMessageMapper messageMapper = mock(KnowledgeChatMessageMapper.class);
    private final RagModelClient modelClient = mock(RagModelClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ConversationContextService service = new ConversationContextService(
            sessionMapper,messageMapper,new ConversationTokenEstimator(),modelClient,new RagProperties(),objectMapper);

    @Test
    void buildsContextOnlyFromOwnedSessionAndPersistsImmutableSnapshot() {
        KnowledgeChatMessage task = message(12L,3L,7L,4L,"assistant","正在生成回答…");
        task.setSourceMessageId(11L);
        KnowledgeChatMessage source = message(11L,3L,7L,3L,"user","它怎么配置？");
        KnowledgeChatMessage previousUser = message(9L,3L,7L,1L,"user","RAG 查询改写功能");
        KnowledgeChatMessage previousAnswer = message(10L,3L,7L,2L,"assistant","可以开启查询改写配置。");
        KnowledgeChatSession session = new KnowledgeChatSession();
        session.setId(3L); session.setUserId(7L); session.setSummaryVersion(0);
        when(messageMapper.getOwned(11L,3L,7L)).thenReturn(source);
        when(sessionMapper.getActive(3L,7L)).thenReturn(session);
        when(messageMapper.listContextCandidates(3L,7L,3L)).thenReturn(List.of(previousUser,previousAnswer));
        when(messageMapper.saveContextSnapshot(eq(12L),any(),any(),eq(2),any())).thenReturn(1);

        ConversationContextSnapshot snapshot = service.resolve(task);

        assertEquals(List.of(9L,10L),snapshot.messageIds());
        assertEquals(2,snapshot.history().size());
        assertEquals("RAG 查询改写功能",snapshot.history().get(0).getContent());
        assertTrue(snapshot.totalTokens() > 0);
        verify(messageMapper).listContextCandidates(3L,7L,3L);
        verify(messageMapper).saveContextSnapshot(eq(12L),any(),any(),eq(2),eq(snapshot.totalTokens()));
    }

    @Test
    void retryUsesStoredSnapshotWithoutRebuildingFromMutableSessionState() throws Exception {
        ConversationContextSnapshot stored = new ConversationContextSnapshot(
                1,3L,7L,12L,11L,List.of(9L),List.of(),4,0,8,0,List.of(),"server-token-budget-v1",LocalDateTime.now());
        KnowledgeChatMessage task = message(12L,3L,7L,4L,"assistant","failed");
        task.setContextSnapshotJson(objectMapper.writeValueAsString(stored));

        ConversationContextSnapshot restored = service.resolve(task);

        assertEquals(stored.messageIds(),restored.messageIds());
        assertEquals(stored.totalTokens(),restored.totalTokens());
        verify(messageMapper,never()).listContextCandidates(any(),any(),any());
    }

    @Test
    void snapshotNeverExceedsConfiguredTokenBudget() {
        RagProperties properties = new RagProperties();
        properties.getChat().setMaxHistoryTokens(12);
        ConversationContextService budgeted = new ConversationContextService(
                sessionMapper,messageMapper,new ConversationTokenEstimator(),modelClient,properties,objectMapper);
        KnowledgeChatMessage task = message(12L,3L,7L,4L,"assistant","pending"); task.setSourceMessageId(11L);
        KnowledgeChatMessage source = message(11L,3L,7L,3L,"user","继续说明配置");
        KnowledgeChatSession session = new KnowledgeChatSession();
        session.setId(3L); session.setUserId(7L); session.setSummaryVersion(1);
        session.setRollingSummary("这是一个很长的较早会话摘要，需要按照 token 预算进行截断。");
        when(messageMapper.getOwned(11L,3L,7L)).thenReturn(source);
        when(sessionMapper.getActive(3L,7L)).thenReturn(session);
        when(messageMapper.listContextCandidates(3L,7L,3L)).thenReturn(List.of());

        ConversationContextSnapshot snapshot = budgeted.resolve(task);

        assertTrue(snapshot.totalTokens() <= 12);
    }

    @Test
    void protectedHistoryPersistsOnlyCurrentlyAuthorizedContentAndTokenCounts() throws Exception {
        RagEvidenceService evidence = mock(RagEvidenceService.class);
        service.setEvidenceService(evidence);
        KnowledgeChatMessage allowed = message(8L,3L,7L,1L,"assistant","unchecked stale answer");
        allowed.setTrustedAnswerId("allowed");
        KnowledgeChatMessage revoked = message(9L,3L,7L,2L,"assistant","revoked-secret");
        revoked.setTrustedAnswerId("revoked");
        KnowledgeChatMessage task = protectedTask(List.of(allowed,revoked));
        RagTrustedAnswer trusted = new RagTrustedAnswer(); trusted.setContent("已验证的配置说明");
        when(evidence.readAnswer("allowed",7L)).thenReturn(trusted);
        when(evidence.readAnswer("revoked",7L)).thenThrow(new BaseException("权限已撤销"));

        ConversationContextSnapshot snapshot = service.resolve(task);

        assertEquals(List.of(8L),snapshot.messageIds());
        assertEquals("已验证的配置说明",snapshot.history().get(0).getContent());
        assertFalse(task.getContextSnapshotJson().contains("revoked-secret"));
        assertFalse(task.getContextSnapshotJson().contains("unchecked stale answer"));
        assertEquals(snapshot.totalTokens(),task.getContextTokenCount());
        assertEquals(snapshot,objectMapper.readValue(task.getContextSnapshotJson(),ConversationContextSnapshot.class));
        verify(messageMapper).saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),eq(snapshot.totalTokens()));

        ConversationContextSnapshot finalized = service.finalizeRetrieval(task,snapshot,List.of(31L,31L,32L));

        assertEquals(List.of(31L,32L),finalized.knowledgeChunkIds());
        assertEquals(finalized,objectMapper.readValue(task.getContextSnapshotJson(),ConversationContextSnapshot.class));
        verify(messageMapper,times(2)).saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),eq(snapshot.totalTokens()));
        verify(messageMapper,never()).finalizeContextSnapshot(any(),any(),any());
        verify(modelClient,never()).generate(any());
    }

    @Test
    void protectedRetryReauthorizesAndReplacesSavedSnapshotAfterRevocation() {
        RagEvidenceService evidence = mock(RagEvidenceService.class);
        service.setEvidenceService(evidence);
        KnowledgeChatMessage previous = message(9L,3L,7L,1L,"assistant","cached-secret");
        previous.setTrustedAnswerId("previous");
        KnowledgeChatMessage task = protectedTask(List.of(previous));
        RagTrustedAnswer answer = new RagTrustedAnswer(); answer.setContent("previously-authorized-secret");
        when(evidence.readAnswer("previous",7L)).thenReturn(answer).thenThrow(new BaseException("权限已撤销"));

        ConversationContextSnapshot first = service.resolve(task);
        ConversationContextSnapshot retry = service.resolve(task);

        assertEquals(List.of(9L),first.messageIds());
        assertTrue(retry.history().isEmpty());
        assertTrue(retry.messageIds().isEmpty());
        assertFalse(task.getContextSnapshotJson().contains("previously-authorized-secret"));
        assertTrue(retry.totalTokens()<first.totalTokens());
        verify(evidence,times(2)).readAnswer("previous",7L);
        verify(messageMapper,times(2)).listContextCandidates(3L,7L,3L);
        verify(messageMapper,times(2)).saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),any());
    }

    @Test
    void protectedResolveRejectsSupersededWorkerWithoutReplacingLocalSnapshot() {
        service.setEvidenceService(mock(RagEvidenceService.class));
        KnowledgeChatMessage task = protectedTask(List.of());
        task.setContextSnapshotJson("old-snapshot");
        when(messageMapper.saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),any())).thenReturn(0);

        assertThrows(StaleTaskException.class,()->service.resolve(task));

        assertEquals("old-snapshot",task.getContextSnapshotJson());
        assertEquals(4L,task.getAsyncVersion());
        assertEquals(21L,task.getAsyncTaskId());
    }

    @Test
    void protectedFinalizationRejectsSupersededWorkerWithoutPublishingNewChunkSnapshot() {
        service.setEvidenceService(mock(RagEvidenceService.class));
        KnowledgeChatMessage task = protectedTask(List.of());
        ConversationContextSnapshot snapshot = service.resolve(task);
        String saved = task.getContextSnapshotJson();
        when(messageMapper.saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),any())).thenReturn(0);

        assertThrows(StaleTaskException.class,()->service.finalizeRetrieval(task,snapshot,List.of(31L)));

        assertEquals(saved,task.getContextSnapshotJson());
        verify(messageMapper,never()).finalizeContextSnapshot(any(),any(),any());
    }

    private KnowledgeChatMessage protectedTask(List<KnowledgeChatMessage> history) {
        KnowledgeChatMessage task=message(12L,3L,7L,4L,"assistant","pending");
        task.setSourceMessageId(11L); task.setAsyncVersion(4L); task.setAsyncTaskId(21L);
        KnowledgeChatMessage source=message(11L,3L,7L,3L,"user","继续说明配置");
        KnowledgeChatSession session=new KnowledgeChatSession(); session.setId(3L); session.setUserId(7L);
        session.setRollingSummary("unverified legacy summary");
        when(messageMapper.getOwned(11L,3L,7L)).thenReturn(source);
        when(sessionMapper.getActive(3L,7L)).thenReturn(session);
        when(messageMapper.listContextCandidates(3L,7L,3L)).thenReturn(history);
        when(messageMapper.saveAuthorizedContextSnapshot(eq(task),any(),any(),eq(2),any())).thenReturn(1);
        return task;
    }

    private KnowledgeChatMessage message(Long id, Long sessionId, Long userId, Long sequence, String role, String content) {
        KnowledgeChatMessage message = new KnowledgeChatMessage();
        message.setId(id); message.setSessionId(sessionId); message.setUserId(userId);
        message.setSequenceNo(sequence); message.setRole(role); message.setContent(content);
        return message;
    }
}
