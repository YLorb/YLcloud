package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedAsyncTaskMapper;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.service.knowledge.event.PipelineEventService;
import com.ylcloud.service.rag.RagModelClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgePipelineMqMigrationTest {
    @Test
    void documentSubmissionAtomicallyRegistersIdOnlyUnifiedTask() {
        Fixture f=new Fixture();
        SpaceRagDocument document=document(9L,7L,3L);
        when(f.documents.getById(9L)).thenReturn(document);
        when(f.rag.getBySpaceId(7L)).thenReturn(enabledConfig());
        when(f.tasks.insert(any())).thenAnswer(invocation -> {
            SpaceKnowledgePipelineTask task=invocation.getArgument(0);
            task.setId(41L);
            return 1;
        });
        when(f.tasks.bindAsyncTask(eq(41L),eq(3L),eq(81L),any())).thenReturn(1);
        when(f.tasks.getById(41L)).thenAnswer(invocation -> f.insertedTask);
        when(f.center.createTask(any())).thenAnswer(invocation -> {
            f.command=invocation.getArgument(0);
            UnifiedAsyncTask task=new UnifiedAsyncTask();
            task.setId(81L);
            return task;
        });
        f.captureInsertedTask();

        f.service().submitDocumentProfileTask(7L,9L,5L);

        assertEquals("KNOWLEDGE_PROFILE_DOCUMENT",f.command.taskType());
        assertEquals("knowledge",f.command.taskDomain());
        assertEquals(3L,f.command.resourceVersion());
        assertEquals(41L,assertInstanceOf(DomainTaskPayload.class,f.command.payload()).resourceId());
        assertEquals(81L,f.insertedTask.getAsyncTaskId());
    }

    @Test
    void spaceWorkerOnlyFansOutIdempotentDocumentTasks() {
        Fixture f=new Fixture();
        SpaceKnowledgePipelineTask parent=pipelineTask(40L,7L,null,70L,1L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_SPACE);
        SpaceRagDocument first=document(9L,7L,2L);
        SpaceRagDocument second=document(10L,7L,4L);
        when(f.tasks.getById(40L)).thenReturn(parent);
        when(f.tasks.claimAsync(eq(40L),eq(1L),eq(70L),any())).thenReturn(1);
        when(f.tasks.finishAsync(anyLong(),anyLong(),anyLong(),any(),any(),anyLongAsInt(),
                anyLongAsInt(),anyLongAsInt(),any(),any(),any())).thenReturn(1);
        when(f.documents.listBySpaceId(7L)).thenReturn(List.of(first,second));
        when(f.documents.getById(9L)).thenReturn(first);
        when(f.documents.getById(10L)).thenReturn(second);
        when(f.rag.getBySpaceId(7L)).thenReturn(enabledConfig());
        when(f.tasks.getByParentDocument(anyLong(),anyLong())).thenReturn(null);
        when(f.tasks.insert(any())).thenAnswer(invocation -> {
            SpaceKnowledgePipelineTask child=invocation.getArgument(0);
            child.setId(child.getDocumentId()+100L);
            f.insertedTask=child;
            return 1;
        });
        when(f.tasks.bindAsyncTask(anyLong(),anyLong(),anyLong(),any())).thenReturn(1);
        when(f.tasks.getById(anyLong())).thenAnswer(invocation ->
                Long.valueOf(40L).equals(invocation.getArgument(0)) ? parent : f.insertedTask);
        when(f.center.createTask(any())).thenAnswer(invocation -> {
            UnifiedAsyncTask task=new UnifiedAsyncTask();
            task.setId(200L+((DomainTaskPayload)((TaskCreateCommand)invocation.getArgument(0)).payload()).resourceId());
            return task;
        });
        TaskExecutionContext context=mock(TaskExecutionContext.class);

        KnowledgePipelineService.SpaceFanoutResult result=f.service().executeSpaceAsync(40L,70L,1L,1,false,context);

        assertEquals(2,result.dispatched());
        verify(f.center,times(2)).createTask(any());
        verify(f.model,never()).generate(any());
        verify(context,times(3)).checkpoint();
    }

    @Test
    void legacyExecutorIsDisabledOnlyAfterKnowledgeFlagSwitchesOn() {
        KnowledgePipelineService pipeline=mock(KnowledgePipelineService.class);
        KnowledgePipelineExecutorService executor=new KnowledgePipelineExecutorService(pipeline);
        AsyncMqProperties properties=enabledProperties();
        executor.setMqProperties(properties);
        executor.runDocumentTask(1L);
        executor.runSpaceTask(2L);
        verify(pipeline,never()).executeTask(anyLong());
        verify(pipeline,never()).executeSpaceTask(anyLong());

        properties.setKnowledge(false);
        executor.runDocumentTask(1L);
        executor.runSpaceTask(2L);
        verify(pipeline).executeTask(1L);
        verify(pipeline).executeSpaceTask(2L);
    }

    @Test
    void staleRecoveryLeavesUnifiedLeasedTasksToTaskCenter() {
        SpaceKnowledgePipelineTaskMapper mapper=mock(SpaceKnowledgePipelineTaskMapper.class);
        com.ylcloud.config.RagProperties ragProperties=new com.ylcloud.config.RagProperties();
        SpaceKnowledgePipelineTask unified=pipelineTask(1L,7L,9L,80L,2L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT);
        SpaceKnowledgePipelineTask legacy=pipelineTask(2L,7L,10L,null,1L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT);
        when(mapper.listStaleActive(any(),anyLongAsInt())).thenReturn(List.of(unified,legacy));
        KnowledgePipelineTaskRecoveryService recovery=new KnowledgePipelineTaskRecoveryService(mapper,ragProperties);
        recovery.setMqProperties(enabledProperties());

        recovery.expireStaleTasks();

        verify(mapper,never()).failActive(org.mockito.ArgumentMatchers.eq(1L),any(),any());
        verify(mapper).failActive(org.mockito.ArgumentMatchers.eq(2L),any(),any());
    }

    @Test
    void profileCommitRejectsCanceledOrNewerUnifiedAttempt() {
        var profiles=mock(com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper.class);
        var questions=mock(com.ylcloud.mapper.SpaceKnowledgeQuestionMapper.class);
        KnowledgeProfileWriteService write=new KnowledgeProfileWriteService(
                profiles,questions,mock(KnowledgeProfileAssetService.class));
        SpaceKnowledgePipelineTaskMapper domain=mock(SpaceKnowledgePipelineTaskMapper.class);
        UnifiedAsyncTaskMapper centralMapper=mock(UnifiedAsyncTaskMapper.class);
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        UnifiedAsyncTask central=new UnifiedAsyncTask();
        central.setId(80L);
        central.setStatus("RUNNING");
        central.setAttemptVersion(3);
        SpaceKnowledgePipelineTask pipeline=pipelineTask(1L,7L,9L,80L,2L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT);
        pipeline.setTaskStatus("RUNNING");
        when(centralMapper.getByIdForUpdate(80L)).thenReturn(central);
        when(domain.getByIdForUpdate(1L)).thenReturn(pipeline);
        write.setAsyncFenceMappers(domain,centralMapper);
        write.setDocumentMapper(documents);

        assertThrows(StaleTaskException.class,() -> write.syncRetrievalSourceFenced(
                7L,9L,null,0L,1L,80L,2L,2,"hash"));
        verify(documents,never()).getAnyByIdForUpdate(anyLong());
        verify(profiles,never()).syncRetrievalSource(any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void replacedDocumentIsSkippedBeforeAnyProfileWork() {
        Fixture f=new Fixture();
        SpaceKnowledgePipelineTask pipeline=pipelineTask(1L,7L,9L,80L,2L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT);
        SpaceRagDocument replaced=document(9L,7L,3L);
        when(f.tasks.getById(1L)).thenReturn(pipeline);
        when(f.tasks.claimAsync(eq(1L),eq(2L),eq(80L),any())).thenReturn(1);
        when(f.documents.getById(9L)).thenReturn(replaced);

        assertThrows(StaleTaskException.class,() -> f.service().executeDocumentAsync(
                1L,80L,2L,1,false,mock(TaskExecutionContext.class)));

        verify(f.tasks).finishAsync(eq(1L),eq(2L),eq(80L),
                eq(SpaceConstant.KNOWLEDGE_TASK_SKIPPED),eq(SpaceConstant.KNOWLEDGE_STAGE_SKIPPED),
                eq(1),eq(0),eq(0),eq(null),eq("RESOURCE_VERSION_STALE"),any());
        verify(f.model,never()).generate(any());
    }

    @Test
    void manualRetryReusesUnifiedTaskAndLegacyListExcludesBoundRows() {
        Fixture f=new Fixture();
        SpaceKnowledgePipelineTask failed=pipelineTask(1L,7L,9L,80L,2L,
                SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT);
        failed.setTaskStatus(SpaceConstant.KNOWLEDGE_TASK_FAILED);
        when(f.tasks.getById(1L)).thenReturn(failed);
        when(f.tasks.prepareAsyncRetry(eq(1L),eq(2L),eq(80L),any())).thenReturn(1);

        f.service().retryTask(7L,1L,5L);

        verify(f.center).retry(80L,5L,"Retry knowledge pipeline task");
        verify(f.tasks).prepareAsyncRetry(eq(1L),eq(2L),eq(80L),any());
        assertTrue(com.ylcloud.async.task.UnifiedTaskQueryMapper.MERGED.contains("k.async_task_id is null"));
    }

    private static int anyLongAsInt() { return org.mockito.ArgumentMatchers.anyInt(); }

    private static AsyncMqProperties enabledProperties() {
        AsyncMqProperties properties=new AsyncMqProperties();
        properties.setEnabled(true);
        properties.setKnowledge(true);
        return properties;
    }

    private static SpaceRagConfig enabledConfig() {
        SpaceRagConfig config=new SpaceRagConfig();
        config.setKnowledgeProfileEnabled(StatusConstant.ENABLE);
        return config;
    }

    private static SpaceRagDocument document(Long id,Long spaceId,Long version) {
        SpaceRagDocument document=new SpaceRagDocument();
        document.setId(id);
        document.setSpaceId(spaceId);
        document.setStatus(StatusConstant.ENABLE);
        document.setIndexStatus(SpaceConstant.RAG_INDEX_SUCCESS);
        document.setConsistencyVersion(version);
        document.setFileHash("hash-"+id);
        document.setFileName("file-"+id+".md");
        return document;
    }

    private static SpaceKnowledgePipelineTask pipelineTask(Long id,Long spaceId,Long documentId,Long asyncId,
                                                            Long version,String type) {
        SpaceKnowledgePipelineTask task=new SpaceKnowledgePipelineTask();
        task.setId(id);
        task.setSpaceId(spaceId);
        task.setDocumentId(documentId);
        task.setAsyncTaskId(asyncId);
        task.setResourceVersion(version);
        task.setTaskType(type);
        task.setTaskStatus(SpaceConstant.KNOWLEDGE_TASK_PENDING);
        task.setCreatedBy(5L);
        return task;
    }

    private static class Fixture {
        final SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        final SpaceKnowledgePipelineTaskMapper tasks=mock(SpaceKnowledgePipelineTaskMapper.class);
        final SpaceRagMapper rag=mock(SpaceRagMapper.class);
        final UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        final PipelineEventService events=mock(PipelineEventService.class);
        final RagModelClient model=mock(RagModelClient.class);
        final SpacePermissionService permissions=mock(SpacePermissionService.class);
        SpaceKnowledgePipelineTask insertedTask;
        TaskCreateCommand command;

        KnowledgePipelineService service() {
            KnowledgePipelineService service=new KnowledgePipelineService(
                    permissions,documents,null,null,null,tasks,null,null,events,null,null,null,null,null,null,null,
                    model,null,rag,new ObjectMapper());
            service.setAsyncTaskInfrastructure(center,enabledProperties());
            return service;
        }

        void captureInsertedTask() {
            try {
                org.mockito.Mockito.doAnswer(invocation -> {
                    insertedTask=invocation.getArgument(0);
                    insertedTask.setId(41L);
                    return 1;
                }).when(tasks).insert(any());
            } catch(Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }
}
