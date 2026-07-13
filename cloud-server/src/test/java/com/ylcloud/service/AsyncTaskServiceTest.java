package com.ylcloud.service;

import com.ylcloud.VO.AsyncTaskVO;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskServiceTest {
    @Test
    void aggregatesOnlyPersistedBackgroundTaskSourcesForCurrentUser() {
        SpaceRagTaskMapper ragMapper = mock(SpaceRagTaskMapper.class);
        SpaceKnowledgePipelineTaskMapper knowledgeMapper = mock(SpaceKnowledgePipelineTaskMapper.class);
        LocalDateTime now = LocalDateTime.now();

        SpaceRagTask rag = new SpaceRagTask();
        rag.setId(7L);
        rag.setSpaceId(3L);
        rag.setTaskType("REBUILD_SPACE");
        rag.setTaskStatus("RUNNING");
        rag.setTotalCount(4);
        rag.setSuccessCount(1);
        rag.setFailedCount(0);
        rag.setCreatetime(now.minusMinutes(1));

        SpaceKnowledgePipelineTask knowledge = new SpaceKnowledgePipelineTask();
        knowledge.setId(9L);
        knowledge.setSpaceId(3L);
        knowledge.setTaskType("PROFILE_DOCUMENT");
        knowledge.setTaskStatus("FAILED");
        knowledge.setStage("GENERATE_PROFILE");
        knowledge.setProgress(65);
        knowledge.setCreatetime(now);

        when(ragMapper.listByCreatedBy(11L,3L,100)).thenReturn(List.of(rag));
        when(knowledgeMapper.listByCreatedBy(11L,3L,100)).thenReturn(List.of(knowledge));

        List<AsyncTaskVO> tasks = new AsyncTaskService(ragMapper,knowledgeMapper).listUserTasks(11L,3L);

        assertEquals(List.of("knowledge","rag"),tasks.stream().map(AsyncTaskVO::getSource).toList());
        assertEquals(25,tasks.get(1).getProgress());
        assertTrue(tasks.get(0).getRetryable());
        verify(ragMapper).listByCreatedBy(11L,3L,100);
        verify(knowledgeMapper).listByCreatedBy(11L,3L,100);
    }
}
