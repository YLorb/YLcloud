package com.ylcloud.service.memory;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatFeedbackMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryItemMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserMemoryManagementServiceTest {
    @Test
    void neverMutatesMemoryOwnedByAnotherUser() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryService memoryService=mock(UserMemoryService.class);
        UserMemoryManagementService service=new UserMemoryManagementService(mapper,memoryService,new RagProperties(),
                mock(KnowledgeChatMessageMapper.class),mock(KnowledgeChatFeedbackMapper.class));
        when(mapper.getOwned(9L,7L)).thenReturn(null);

        assertThrows(BaseException.class,() -> service.pin(7L,9L,true));

        verify(mapper,never()).setPinned(anyLong(),anyLong(),anyBoolean(),any());
        verifyNoInteractions(memoryService);
    }

    @Test
    void forgettingOwnedMemoryDelegatesToCompensatedDeletion() {
        UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
        UserMemoryService memoryService=mock(UserMemoryService.class);
        UserMemoryManagementService service=new UserMemoryManagementService(mapper,memoryService,new RagProperties(),
                mock(KnowledgeChatMessageMapper.class),mock(KnowledgeChatFeedbackMapper.class));
        when(mapper.getOwned(9L,7L)).thenReturn(new UserMemoryItem());

        service.forget(7L,9L);

        verify(memoryService).forget(7L,9L);
    }
}
