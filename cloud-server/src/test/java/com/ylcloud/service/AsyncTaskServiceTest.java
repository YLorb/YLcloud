package com.ylcloud.service;

import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.AsyncTaskDetailVO;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncTaskServiceTest {
    private final SpaceRagTaskMapper rag = mock(SpaceRagTaskMapper.class);
    private final SpaceKnowledgePipelineTaskMapper knowledge = mock(SpaceKnowledgePipelineTaskMapper.class);
    private final AsyncTaskService service = new AsyncTaskService(
            rag,
            knowledge,
            mock(com.ylcloud.async.task.UnifiedTaskQueryMapper.class),
            mock(com.ylcloud.async.task.UnifiedAsyncTaskMapper.class),
            mock(com.ylcloud.async.task.UnifiedTaskCenterService.class),
            mock(com.ylcloud.async.task.TaskAuthorizationService.class)
    );

    @Test
    void returnsOwnedRagTaskCompletionDetails() {
        SpaceRagTask task = new SpaceRagTask();
        task.setId(7L);
        task.setCreatedBy(42L);
        task.setTaskStatus("SUCCESS");
        task.setSuccessCount(3);
        task.setFailedCount(0);
        task.setTotalCount(3);
        task.setStartedTime(LocalDateTime.of(2026,7,17,10,0));
        task.setFinishedTime(LocalDateTime.of(2026,7,17,10,0,2));
        when(rag.getById(7L)).thenReturn(task);

        AsyncTaskDetailVO detail = service.getUserTask(42L,"rag",7L);

        assertEquals("成功处理 3 项，失败 0 项",detail.getCompletionSummary());
        assertEquals(2000L,detail.getDurationMs());
        assertEquals(3,detail.getSuccessCount());
    }

    @Test
    void hidesTasksOwnedByAnotherUser() {
        SpaceRagTask task = new SpaceRagTask();
        task.setId(7L);
        task.setCreatedBy(99L);
        when(rag.getById(7L)).thenReturn(task);

        assertThrows(NotFoundException.class,() -> service.getUserTask(42L,"rag",7L));
    }

    @Test
    void redactsSecretsFromKnowledgeFailureDetails() {
        SpaceKnowledgePipelineTask task = new SpaceKnowledgePipelineTask();
        task.setId(9L);
        task.setCreatedBy(42L);
        task.setTaskStatus("FAILED");
        task.setFailedCount(1);
        task.setErrorMessage("token=abc123 password:secret-value");
        when(knowledge.getById(9L)).thenReturn(task);

        AsyncTaskDetailVO detail = service.getUserTask(42L,"knowledge",9L);

        assertTrue(detail.getErrorMessage().contains("[REDACTED]"));
        assertTrue(!detail.getErrorMessage().contains("abc123"));
        assertTrue(!detail.getErrorMessage().contains("secret-value"));
    }

    @Test
    void redactsSecretsFromAggregatedTaskList() {
        SpaceRagTask task = new SpaceRagTask();
        task.setId(10L);
        task.setCreatedBy(42L);
        task.setTaskStatus("FAILED");
        task.setErrorMessage("Bearer abc.def token=private-token");
        when(rag.listByCreatedBy(42L,null,100)).thenReturn(List.of(task));
        when(knowledge.listByCreatedBy(42L,null,100)).thenReturn(List.of());

        var listed = service.listUserTasks(42L,null).get(0);

        assertTrue(listed.getErrorMessage().contains("[REDACTED]"));
        assertTrue(!listed.getErrorMessage().contains("private-token"));
        assertTrue(!listed.getMessage().contains("abc.def"));
    }
}
