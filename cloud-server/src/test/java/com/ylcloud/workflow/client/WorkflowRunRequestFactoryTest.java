package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowRunRequestFactoryTest {
    @Test
    void buildsDeterministicInputHashWithoutPerformingRetrieval() throws Exception {
        KnowledgeChatMessageMapper mapper = mock(KnowledgeChatMessageMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowRunRequestFactory factory = new WorkflowRunRequestFactory(mapper, objectMapper);
        KnowledgeChatQueryCreateDTO dto = new KnowledgeChatQueryCreateDTO();
        dto.setQuestion("ignored duplicate");
        dto.setSpaceIds(List.of(5L, 5L, 7L));
        dto.setRetrievalMode("balanced");
        KnowledgeChatMessage assistant = message(9L, "assistant", "", 8L, 7L);
        assistant.setSourceMessageId(6L);
        assistant.setRequestJson(objectMapper.writeValueAsString(dto));
        KnowledgeChatMessage source = message(6L, "user", "actual question", 8L, 7L);
        source.setSequenceNo(10L);
        KnowledgeChatMessage history = message(4L, "assistant", "previous answer", 8L, 7L);
        when(mapper.getOwned(6L, 8L, 7L)).thenReturn(source);
        when(mapper.listContextCandidates(8L, 7L, 10L)).thenReturn(List.of(history));

        var first = factory.create(assistant);
        var second = factory.create(assistant);

        assertEquals(first.request().requestContextHash(), second.request().requestContextHash());
        assertEquals(first.idempotencyKey(), second.idempotencyKey());
        assertEquals("actual question", first.request().question());
        assertEquals(List.of(5L, 7L), first.request().knowledgeScope().selectedSpaceIds());
        assertTrue(first.request().knowledgeScope().explicitSelection());
        assertEquals("previous answer", first.request().shortTermContext().get(0).content());
    }

    private KnowledgeChatMessage message(Long id, String role, String content, Long sessionId, Long userId) {
        KnowledgeChatMessage message = new KnowledgeChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setSessionId(sessionId);
        message.setUserId(userId);
        return message;
    }
}
