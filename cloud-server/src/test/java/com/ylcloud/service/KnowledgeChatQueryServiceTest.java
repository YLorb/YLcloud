package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatQueryServiceTest {
    private final KnowledgeChatSessionMapper sessionMapper = mock(KnowledgeChatSessionMapper.class);
    private final KnowledgeChatMessageMapper messageMapper = mock(KnowledgeChatMessageMapper.class);
    private final SpacePermissionService permissionService = mock(SpacePermissionService.class);
    private final KnowledgeRagQueryService ragQueryService = mock(KnowledgeRagQueryService.class);
    private final KnowledgeChatQueryService service = new KnowledgeChatQueryService(
            sessionMapper,messageMapper,permissionService,ragQueryService,new ObjectMapper(),Runnable::run);

    @Test
    void submitPersistsUserAndRecoverableAssistantMessages() {
        KnowledgeChatSession session = new KnowledgeChatSession(); session.setId(3L); session.setUserId(7L);
        when(sessionMapper.getActive(3L,7L)).thenReturn(session);
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
        KnowledgeRagQueryVO result = new KnowledgeRagQueryVO(); result.setAnswer("answer"); result.setCitations(List.of());
        when(ragQueryService.query(any(),eq(7L))).thenReturn(result);

        service.execute(11L);

        verify(messageMapper).markSuccess(11L,"answer","[]");
    }

    private KnowledgeChatQueryCreateDTO request() { KnowledgeChatQueryCreateDTO dto = new KnowledgeChatQueryCreateDTO(); dto.setQuestion("question"); dto.setSpaceIds(List.of(5L)); dto.setRetrievalMode("balanced"); return dto; }
}
