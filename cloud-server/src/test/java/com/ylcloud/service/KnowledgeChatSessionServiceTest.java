package com.ylcloud.service;

import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeChatSessionServiceTest {
    @Test
    void deletingSessionOnlyDisablesConversationData() {
        KnowledgeChatSessionMapper sessionMapper=mock(KnowledgeChatSessionMapper.class);
        KnowledgeChatMessageMapper messageMapper=mock(KnowledgeChatMessageMapper.class);
        KnowledgeChatSessionService service=new KnowledgeChatSessionService(
                sessionMapper,messageMapper,mock(SpacePermissionService.class));
        when(sessionMapper.getActive(9L,7L)).thenReturn(new KnowledgeChatSession());
        when(sessionMapper.disable(eq(9L),eq(7L),any())).thenReturn(1);

        Boolean deleted=service.delete(7L,9L);

        assertThat(deleted).isTrue();
        verify(messageMapper).disableBySessionId(9L);
        verify(sessionMapper).disable(eq(9L),eq(7L),any());
        verifyNoMoreInteractions(messageMapper);
    }
}
