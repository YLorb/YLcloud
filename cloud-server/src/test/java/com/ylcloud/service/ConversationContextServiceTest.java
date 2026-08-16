package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.service.rag.RagModelClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private KnowledgeChatMessage message(Long id, Long sessionId, Long userId, Long sequence, String role, String content) {
        KnowledgeChatMessage message = new KnowledgeChatMessage();
        message.setId(id); message.setSessionId(sessionId); message.setUserId(userId);
        message.setSequenceNo(sequence); message.setRole(role); message.setContent(content);
        return message;
    }
}
