package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgePipelineServiceTest {
    private final KnowledgePipelineService service = new KnowledgePipelineService(
            null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,new ObjectMapper()
    );

    @Test
    void parsesJsonObjectFromModelText() {
        String text = """
                ```json
                {
                  "title": "RAG Guide",
                  "summary": "A short guide.",
                  "keywords": ["rag", "qdrant"],
                  "tags": ["knowledge", "search"],
                  "category": "engineering",
                  "language": "en",
                  "documentType": "guide",
                  "questions": ["How does retrieval work?"]
                }
                ```
                """;

        KnowledgePipelineService.GeneratedProfile profile = service.parseGeneratedProfile(text);

        assertEquals("RAG Guide",profile.title());
        assertEquals(List.of("rag","qdrant"),profile.keywords());
        assertEquals(List.of("knowledge","search"),profile.tags());
        assertEquals("engineering",profile.category());
        assertEquals(List.of("How does retrieval work?"),profile.questions());
    }

    @Test
    void badModelJsonReturnsEmptyProfile() {
        KnowledgePipelineService.GeneratedProfile profile = service.parseGeneratedProfile("not-json");

        assertTrue(profile.keywords().isEmpty());
        assertTrue(profile.tags().isEmpty());
        assertTrue(profile.questions().isEmpty());
    }

    @Test
    void spaceTaskWithNoRagReadyDocumentsIsSkipped() {
        SpaceRagDocumentMapper documentMapper = mock(SpaceRagDocumentMapper.class);
        SpaceKnowledgePipelineTaskMapper taskMapper = mock(SpaceKnowledgePipelineTaskMapper.class);
        SpaceRagMapper ragMapper = mock(SpaceRagMapper.class);
        SpaceKnowledgePipelineTask task = task(42L,7L,null);
        SpaceRagConfig config = new SpaceRagConfig();
        config.setKnowledgeProfileEnabled(StatusConstant.ENABLE);
        when(taskMapper.getById(42L)).thenReturn(task);
        when(taskMapper.markRunningIfPending(eq(42L),any(LocalDateTime.class))).thenReturn(1);
        when(documentMapper.listBySpaceId(7L)).thenReturn(List.of());
        when(ragMapper.getBySpaceId(7L)).thenReturn(config);

        pipelineService(documentMapper,taskMapper,ragMapper).executeSpaceTask(42L);

        verify(taskMapper).updateFlowIfRunning(eq(42L),eq(SpaceConstant.KNOWLEDGE_TASK_SKIPPED),
                eq(SpaceConstant.KNOWLEDGE_STAGE_SKIPPED),eq(100),eq(0),eq(0),eq(0),
                isNull(),isNull(),any(LocalDateTime.class),any(LocalDateTime.class));
        verify(taskMapper).updateIncremental(eq(42L),eq(SpaceConstant.KNOWLEDGE_STAGE_SKIPPED),
                eq(SpaceConstant.KNOWLEDGE_TERMINAL_NO_ELIGIBLE_DOCUMENTS),isNull(),isNull(),any(LocalDateTime.class));
    }

    @Test
    void queuedDocumentTaskIsSkippedWhenProfileFeatureWasDisabled() {
        SpaceRagDocumentMapper documentMapper = mock(SpaceRagDocumentMapper.class);
        SpaceKnowledgePipelineTaskMapper taskMapper = mock(SpaceKnowledgePipelineTaskMapper.class);
        SpaceRagMapper ragMapper = mock(SpaceRagMapper.class);
        SpaceKnowledgePipelineTask task = task(43L,7L,9L);
        SpaceRagConfig config = new SpaceRagConfig();
        config.setKnowledgeProfileEnabled(StatusConstant.DISABLE);
        when(taskMapper.getById(43L)).thenReturn(task);
        when(taskMapper.markRunningIfPending(eq(43L),any(LocalDateTime.class))).thenReturn(1);
        when(ragMapper.getBySpaceId(7L)).thenReturn(config);

        pipelineService(documentMapper,taskMapper,ragMapper).executeTask(43L);

        verify(taskMapper).updateIncremental(eq(43L),eq(SpaceConstant.KNOWLEDGE_STAGE_SKIPPED),
                eq(SpaceConstant.KNOWLEDGE_TERMINAL_PROFILE_DISABLED),isNull(),isNull(),any(LocalDateTime.class));
        verify(documentMapper,never()).getById(any());
    }

    @Test
    void mapsProfileBatchCountsToUnambiguousTerminalStatus() {
        assertEquals(SpaceConstant.KNOWLEDGE_TASK_SUCCESS,KnowledgePipelineService.spaceTaskStatus(3,0));
        assertEquals(SpaceConstant.KNOWLEDGE_TASK_PARTIAL_SUCCESS,KnowledgePipelineService.spaceTaskStatus(2,1));
        assertEquals(SpaceConstant.KNOWLEDGE_TASK_FAILED,KnowledgePipelineService.spaceTaskStatus(0,2));
        assertEquals(SpaceConstant.KNOWLEDGE_TASK_SKIPPED,KnowledgePipelineService.spaceTaskStatus(0,0));
    }

    private KnowledgePipelineService pipelineService(SpaceRagDocumentMapper documentMapper,
                                                       SpaceKnowledgePipelineTaskMapper taskMapper,
                                                       SpaceRagMapper ragMapper) {
        return new KnowledgePipelineService(
                null,documentMapper,null,null,null,taskMapper,null,null,null,null,null,null,null,null,null,null,null,null,ragMapper,new ObjectMapper()
        );
    }

    private SpaceKnowledgePipelineTask task(Long id, Long spaceId, Long documentId) {
        SpaceKnowledgePipelineTask task = new SpaceKnowledgePipelineTask();
        task.setId(id);
        task.setSpaceId(spaceId);
        task.setDocumentId(documentId);
        task.setTaskStatus(SpaceConstant.KNOWLEDGE_TASK_PENDING);
        return task;
    }
}
