package com.ylcloud.service;

import com.ylcloud.DTO.KnowledgeChatFeedbackDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatEpisodeMapper;
import com.ylcloud.mapper.KnowledgeChatFeedbackMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KnowledgeChatEpisodeServiceTest {
    @Test
    void feedbackRequiresOwnedSuccessfulAssistantMessage() {
        KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
        KnowledgeChatFeedbackMapper feedback=mock(KnowledgeChatFeedbackMapper.class);
        KnowledgeChatEpisodeService service=new KnowledgeChatEpisodeService(mock(KnowledgeChatEpisodeMapper.class),
                mock(KnowledgeChatSessionMapper.class),messages,feedback);
        KnowledgeChatMessage user=new KnowledgeChatMessage(); user.setRole("user");
        when(messages.getOwned(3L,2L,1L)).thenReturn(user);
        KnowledgeChatFeedbackDTO dto=new KnowledgeChatFeedbackDTO(); dto.setRating("HELPFUL");

        assertThrows(BaseException.class,() -> service.feedback(1L,2L,3L,dto));
        verifyNoInteractions(feedback);
    }
}
