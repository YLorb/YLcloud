package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.service.rag.RerankResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryRetrievalServiceTest {
    private final UserMemoryService memoryService=mock(UserMemoryService.class);
    private final UserMemoryVectorStoreService vectorStore=mock(UserMemoryVectorStoreService.class);
    private final UserMemoryItemMapper mapper=mock(UserMemoryItemMapper.class);
    private final RagModelClient modelClient=mock(RagModelClient.class);
    private final RagProperties properties=new RagProperties();
    private UserMemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        properties.getMemory().setMinScore(0.25);
        service=new UserMemoryRetrievalService(memoryService,vectorStore,mapper,modelClient,properties);
        UserMemoryItem memory=new UserMemoryItem();
        memory.setId(11L);
        memory.setContent("用户偏好中文回答");
        when(memoryService.enabled(7L)).thenReturn(true);
        when(vectorStore.search(7L,"请继续说明",12)).thenReturn(List.of(
                new UserMemoryVectorStoreService.MemoryVectorHit(11L,0.8)));
        when(mapper.listActiveByIds(7L,List.of(11L))).thenReturn(List.of(memory));
    }

    @Test
    void nullRerankResultMeansCompletedWithoutMemory() {
        when(modelClient.rerank(anyString(),anyList(),anyInt())).thenReturn(null);

        List<UserMemoryItem> result=service.retrieve(7L,"请继续说明");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void emptyRerankResultMeansCompletedWithoutMemory() {
        when(modelClient.rerank(anyString(),anyList(),anyInt())).thenReturn(List.of());

        List<UserMemoryItem> result=service.retrieve(7L,"请继续说明");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void candidatesBelowThresholdDoNotFallBackToVectorOrder() {
        when(modelClient.rerank(anyString(),anyList(),anyInt())).thenReturn(List.of(new RerankResult(0,0.24)));

        List<UserMemoryItem> result=service.retrieve(7L,"请继续说明");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void candidateWithNullScoreIsRejected() {
        when(modelClient.rerank(anyString(),anyList(),anyInt())).thenReturn(List.of(new RerankResult(0,null)));

        List<UserMemoryItem> result=service.retrieve(7L,"请继续说明");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void candidateAtThresholdIsReturned() {
        when(modelClient.rerank(anyString(),anyList(),anyInt())).thenReturn(List.of(new RerankResult(0,0.25)));

        List<UserMemoryItem> result=service.retrieve(7L,"请继续说明");

        assertThat(result).extracting(UserMemoryItem::getId).containsExactly(11L);
    }
}
