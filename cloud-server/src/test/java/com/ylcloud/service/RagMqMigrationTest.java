package com.ylcloud.service;

import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedAsyncTaskMapper;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.*;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.RagChatService;
import com.ylcloud.service.rag.RagRerankService;
import com.ylcloud.service.rag.RagTaskExecutorService;
import com.ylcloud.service.rag.parser.DocumentParser;
import com.ylcloud.service.rag.parser.StructuredChunker;
import com.ylcloud.service.rag.query.QueryRewriteService;
import com.ylcloud.service.rag.retriever.RagMultiRouteRetriever;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagMqMigrationTest {
    @Test
    void importedLogicalFileRegistersIdOnlyUnifiedTaskAndDoesNotUseLegacyExecutor() {
        Fixture f=new Fixture();
        SpaceFile file=spaceFile();
        SpaceRagDocument document=document(31L,7L,42L,3L,StatusConstant.ENABLE);
        when(f.documents.getBySpaceFileId(7L,42L)).thenReturn(document);
        when(f.documents.getAnyById(31L)).thenReturn(document);
        when(f.tasks.insert(any())).thenAnswer(invocation -> {
            SpaceRagTask task=invocation.getArgument(0);
            task.setId(51L);
            return 1;
        });
        when(f.tasks.bindAsyncTask(eq(51L),eq(3L),eq(81L),any())).thenReturn(1);
        when(f.center.createTask(any())).thenAnswer(invocation -> {
            f.command=invocation.getArgument(0);
            UnifiedAsyncTask task=new UnifiedAsyncTask();
            task.setId(81L);
            return task;
        });

        f.service().handleFileImported(file,5L);

        assertEquals("RAG_INDEX_FILE",f.command.taskType());
        assertEquals("rag",f.command.taskDomain());
        assertEquals("rag-file:7:42",f.command.resourceKey());
        assertEquals(3L,f.command.resourceVersion());
        assertEquals(51L,assertInstanceOf(DomainTaskPayload.class,f.command.payload()).resourceId());
        verify(f.executor,never()).runFileTask(anyLong(),anyLong(),anyLong());
    }

    @Test
    void deletionAdvancesFenceAndSupersedesLateIndexBeforePublishingDelete() {
        Fixture f=new Fixture();
        SpaceRagDocument active=document(31L,7L,42L,3L,StatusConstant.ENABLE);
        SpaceRagDocument deleted=document(31L,7L,42L,4L,StatusConstant.DISABLE);
        when(f.documents.getBySpaceFileId(7L,42L)).thenReturn(active);
        when(f.documents.getAnyById(31L)).thenReturn(deleted);
        when(f.tasks.insert(any())).thenAnswer(invocation -> {
            SpaceRagTask task=invocation.getArgument(0);
            task.setId(52L);
            return 1;
        });
        when(f.tasks.bindAsyncTask(eq(52L),eq(4L),eq(82L),any())).thenReturn(1);
        when(f.center.createTask(any())).thenAnswer(invocation -> {
            f.command=invocation.getArgument(0);
            UnifiedAsyncTask task=new UnifiedAsyncTask();
            task.setId(82L);
            return task;
        });

        f.service().handleFileRemoved(7L,42L,5L);

        InOrder order=inOrder(f.documents,f.tasks,f.center);
        order.verify(f.documents).disableBySpaceFileId(eq(7L),eq(42L),eq(SpaceConstant.RAG_INDEX_FAILED),any(),any());
        order.verify(f.tasks).skipActiveFileIndexTasks(eq(7L),eq(42L),any(),any());
        order.verify(f.tasks).insert(any());
        order.verify(f.center).createTask(any());
        assertEquals("RAG_DELETE_FILE",f.command.taskType());
        assertEquals(4L,f.command.resourceVersion());
    }

    @Test
    void legacyRagExecutorBecomesNoOpOnlyWhenRagFlagIsEnabled() {
        SpaceRagService service=mock(SpaceRagService.class);
        RagTaskExecutorService executor=new RagTaskExecutorService(service);
        AsyncMqProperties properties=enabledProperties();
        executor.setMqProperties(properties);
        executor.runFileTask(1L,2L,3L);
        executor.runSpaceTask(4L,5L,6L);
        verifyNoInteractions(service);

        properties.setRag(false);
        executor.runFileTask(1L,2L,3L);
        verify(service).executeFileRagTask(1L,2L,3L);
    }

    @Test
    void fencedCommitRejectsReplacedAttemptBeforeChangingReferences() {
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs=mock(SpaceRagChunkRefMapper.class);
        SpaceRagTaskMapper domain=mock(SpaceRagTaskMapper.class);
        UnifiedAsyncTaskMapper centralMapper=mock(UnifiedAsyncTaskMapper.class);
        RagIndexTransactionService service=new RagIndexTransactionService(documents,refs);
        service.setAsyncFenceMappers(centralMapper,domain);
        UnifiedAsyncTask central=new UnifiedAsyncTask();
        central.setId(80L);
        central.setStatus("RUNNING");
        central.setAttemptVersion(3);
        SpaceRagTask ragTask=new SpaceRagTask();
        ragTask.setId(40L);
        ragTask.setTaskStatus("RUNNING");
        ragTask.setAsyncTaskId(80L);
        ragTask.setResourceVersion(2L);
        when(centralMapper.getByIdForUpdate(80L)).thenReturn(central);
        when(domain.getByIdForUpdate(40L)).thenReturn(ragTask);

        assertThrows(StaleTaskException.class,() -> service.commitIndexFenced(
                7L,42L,31L,java.util.List.of(new com.ylcloud.entity.FileRagChunk()),40L,80L,2L,2));
        verify(documents,never()).getAnyByIdForUpdate(anyLong());
        verify(refs,never()).disableByDocumentId(anyLong(),any(LocalDateTime.class));
    }

    @Test
    void unifiedListExcludesBoundLegacyRagRowsAndCarriesParentLinks() {
        assertTrue(com.ylcloud.async.task.UnifiedTaskQueryMapper.MERGED.contains("r.async_task_id is null"));
        assertTrue(com.ylcloud.async.task.UnifiedTaskQueryMapper.MERGED.contains("t.parent_task_id parentTaskId"));
        assertTrue(com.ylcloud.async.task.UnifiedTaskQueryMapper.MERGED.contains("r.parent_task_id"));
    }

    private static AsyncMqProperties enabledProperties() {
        AsyncMqProperties properties=new AsyncMqProperties();
        properties.setEnabled(true);
        properties.setRag(true);
        return properties;
    }

    private static SpaceFile spaceFile() {
        SpaceFile file=new SpaceFile();
        file.setId(42L);
        file.setSpaceId(7L);
        file.setFileUuid("file-uuid");
        file.setFileName("guide.md");
        file.setCreatedBy(5L);
        file.setDir(0);
        return file;
    }

    private static SpaceRagDocument document(Long id,Long spaceId,Long spaceFileId,Long version,Integer status) {
        SpaceRagDocument document=new SpaceRagDocument();
        document.setId(id);
        document.setSpaceId(spaceId);
        document.setSpaceFileId(spaceFileId);
        document.setConsistencyVersion(version);
        document.setStatus(status);
        document.setIndexStatus(status==StatusConstant.ENABLE ? SpaceConstant.RAG_INDEX_SUCCESS : SpaceConstant.RAG_INDEX_FAILED);
        document.setVectorState(status==StatusConstant.ENABLE ? "ACTIVE" : "CLEANUP_PENDING");
        document.setFileHash("hash-v"+version);
        return document;
    }

    private static class Fixture {
        final SpaceRagMapper rag=mock(SpaceRagMapper.class);
        final SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        final SpaceRagTaskMapper tasks=mock(SpaceRagTaskMapper.class);
        final SpaceRagChunkRefMapper refs=mock(SpaceRagChunkRefMapper.class);
        final UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        final RagTaskExecutorService executor=mock(RagTaskExecutorService.class);
        TaskCreateCommand command;

        SpaceRagService service() {
            SpaceRagService service=new SpaceRagService(
                    rag,documents,mock(FileRagChunkMapper.class),refs,tasks,mock(SpaceRagQueryLogMapper.class),
                    mock(SpaceRagConfigLogMapper.class),mock(SpaceFileMapper.class),mock(FileInfoMapper.class),
                    mock(SpacePermissionService.class),mock(QdrantVectorStoreService.class),mock(RagChatService.class),
                    mock(RagRerankService.class),mock(RagMultiRouteRetriever.class),new RagProperties(),
                    mock(DocumentParser.class),mock(StructuredChunker.class),mock(QueryRewriteService.class),executor,
                    mock(KnowledgePipelineService.class),mock(KnowledgePipelineExecutorService.class),mock(SiteSettingService.class),
                    mock(RagIndexTransactionService.class),mock(RagIndexConsistencyService.class),mock(SpaceFileLifecycleService.class));
            service.setAsyncTaskInfrastructure(center,enabledProperties());
            return service;
        }
    }
}
