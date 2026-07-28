package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.UserLifecycleMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
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

    @Test
    void newMemoryHasNoAutomaticExpiration() {
        UserMemoryItemMapper mapper = mock(UserMemoryItemMapper.class);
        when(mapper.isEnabled(7L)).thenReturn(true);
        UserMemoryService service = new UserMemoryService(mapper,mock(UserMemoryVectorStoreService.class),new RagProperties());

        UserMemoryItem result = service.accept(7L,9L,11L,"项目只部署在内网",new UserMemoryCandidate(
                "CONSTRAINT","deployment.network","项目只部署在内网",0.9,true));

        assertThat(result.getExpiresAt()).isNull();
        verify(mapper).insert(result);
    }

    @Test
    void accountDeletionCancelsExtractionAndBlocksLateMemoryWrite() {
        UserMemoryItemMapper mapper = mock(UserMemoryItemMapper.class);
        UserMemoryExtractionTaskMapper extractionTasks = mock(UserMemoryExtractionTaskMapper.class);
        UserLifecycleMapper lifecycle = mock(UserLifecycleMapper.class);
        User purging = new User();
        purging.setAccountStatus("PURGING");
        when(lifecycle.getAccountStatus(7L)).thenReturn(purging);
        UserMemoryService service = new UserMemoryService(
                mapper, mock(UserMemoryVectorStoreService.class), new RagProperties());
        service.setDeletionFences(extractionTasks, lifecycle);

        service.clear(7L);
        UserMemoryItem result = service.acceptFromTask(7L, 9L, 11L, "late",
                new UserMemoryCandidate("FACT", "late", "late", 1.0, false), 99L);

        assertThat(result).isNull();
        verify(extractionTasks).cancelByUserId(eq(7L), any());
        verify(mapper, never()).insert(any());
    }
}
