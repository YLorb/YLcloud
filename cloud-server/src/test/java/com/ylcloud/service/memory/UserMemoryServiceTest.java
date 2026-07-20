package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.UserMemoryItemMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryServiceTest {
    @Test
    void sourceHashAndNormalizedKeyMakeCandidateIdempotent() {
        UserMemoryItemMapper mapper = mock(UserMemoryItemMapper.class);
        UserMemoryVectorStoreService vectorStore = mock(UserMemoryVectorStoreService.class);
        UserMemoryItem existing = new UserMemoryItem(); existing.setId(42L);
        when(mapper.isEnabled(7L)).thenReturn(true);
        when(mapper.findIdempotent(eq(7L),eq("response.language"),anyString())).thenReturn(existing);
        UserMemoryService service = new UserMemoryService(mapper,vectorStore,new RagProperties());

        UserMemoryItem result = service.accept(7L,9L,11L,"请一直用中文",new UserMemoryCandidate(
                "PREFERENCE"," Response.Language ","用户偏好中文回答",0.9,true));

        assertThat(result.getId()).isEqualTo(42L);
        verify(mapper,never()).insert(any());
        verifyNoInteractions(vectorStore);
    }
}
