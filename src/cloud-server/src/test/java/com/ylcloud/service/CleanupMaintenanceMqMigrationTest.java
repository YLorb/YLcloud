package com.ylcloud.service;

import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.async.worker.TaskExecutionContext;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.UploadTask;
import com.ylcloud.mapper.*;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupMaintenanceMqMigrationTest {
    @Test
    void physicalCleanupCreatesOneUnifiedTaskInsteadOfLaunchingLegacyExecutor() {
        PhysicalFileCleanupTaskMapper tasks=mock(PhysicalFileCleanupTaskMapper.class);
        FileInfoMapper files=mock(FileInfoMapper.class);
        ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        PhysicalFileCleanupTask cleanup=new PhysicalFileCleanupTask();
        cleanup.setId(4L); cleanup.setFileUuid("file-1"); cleanup.setResourceVersion(1L);
        when(tasks.getByFileUuid("file-1")).thenReturn(cleanup);
        when(center.createTask(any())).thenReturn(unified(21L,1));
        PhysicalFileCleanupService service=new PhysicalFileCleanupService(tasks,files,mock(FileVersionMapper.class),
                mock(FileRagChunkMapper.class),mock(FileRagParseResultMapper.class),mock(MinioclientUtil.class),events);
        service.setAsyncTaskInfrastructure(cleanupProperties(),center);

        service.enqueue("file-1");

        verify(tasks).bindAsyncTask(eq(4L),eq(1L),eq(21L),any());
        verifyNoInteractions(events);
        ArgumentCaptor<TaskCreateCommand> command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(center).createTask(command.capture());
        assertEquals("PHYSICAL_FILE_CLEANUP",command.getValue().taskType());
    }

    @Test
    void cleanupFlagMakesLegacyExecutorRegisterPendingRowsWithoutExecutingThem() {
        PhysicalFileCleanupService cleanup=mock(PhysicalFileCleanupService.class);
        PhysicalFileCleanupExecutor executor=new PhysicalFileCleanupExecutor(cleanup);
        executor.setMqProperties(cleanupProperties());

        executor.resumePendingCleanup();

        verify(cleanup).recoverInterrupted();
        verify(cleanup).enqueuePendingAsync();
        verify(cleanup,never()).processPending();
    }

    @Test
    void physicalCleanupRejectsLateDeleteWhenFileWasRegisteredAgain() {
        PhysicalFileCleanupTaskMapper tasks=mock(PhysicalFileCleanupTaskMapper.class);
        FileInfoMapper files=mock(FileInfoMapper.class);
        MinioclientUtil minio=mock(MinioclientUtil.class);
        PhysicalFileCleanupTask cleanup=new PhysicalFileCleanupTask();
        cleanup.setId(4L); cleanup.setFileUuid("file-1"); cleanup.setTaskStatus("PENDING"); cleanup.setAsyncTaskId(21L);
        when(tasks.getById(4L)).thenReturn(cleanup);
        when(files.getFileInfo("file-1",0L)).thenReturn(new com.ylcloud.entity.File());
        PhysicalFileCleanupService service=new PhysicalFileCleanupService(tasks,files,mock(FileVersionMapper.class),
                mock(FileRagChunkMapper.class),mock(FileRagParseResultMapper.class),minio,mock(ApplicationEventPublisher.class));

        assertThrows(com.ylcloud.async.task.StaleTaskException.class,
                () -> service.executeAsync(4L,21L,mock(TaskExecutionContext.class)));

        verifyNoInteractions(minio);
    }

    @Test
    void maintenanceFlagAloneDoesNotDisableLegacyCleanupPath() {
        PhysicalFileCleanupService cleanup=mock(PhysicalFileCleanupService.class);
        PhysicalFileCleanupExecutor executor=new PhysicalFileCleanupExecutor(cleanup);
        executor.setMqProperties(maintenanceProperties());

        executor.resumePendingCleanup();

        verify(cleanup).recoverInterrupted();
        verify(cleanup).processPending();
        verify(cleanup,never()).enqueuePendingAsync();
    }

    @Test
    void multipartScannerOnlyRegistersIdTasksWhenCleanupFlagIsOn() {
        MultifileMapper uploads=mock(MultifileMapper.class);
        MinioclientUtil minio=mock(MinioclientUtil.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        UploadTask merged=upload(7L,2); UploadTask stale=upload(8L,1);
        when(uploads.listMergedPendingCleanup(100)).thenReturn(List.of(merged));
        when(uploads.listStale(any(),eq(100))).thenReturn(List.of(stale));
        when(uploads.markExpired(eq(8L),any())).thenReturn(1);
        when(center.createTask(any())).thenReturn(unified(31L,1));
        MultipartUploadCleanupService service=new MultipartUploadCleanupService(uploads,mock(ChunkUploadMapper.class),minio);
        service.setAsyncTaskInfrastructure(cleanupProperties(),center);

        service.cleanupStaleUploads();

        verify(center,times(2)).createTask(any());
        verify(uploads).bindCleanupTask(eq(7L),eq(31L),any());
        verify(uploads).bindCleanupTask(eq(8L),eq(31L),any());
        verifyNoInteractions(minio);
    }

    @Test
    void staleCrossStoreScannerRegistersMaintenanceTaskWithoutCompensation() {
        CrossStoreOperationService operations=mock(CrossStoreOperationService.class);
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        CrossStoreOperation operation=new CrossStoreOperation();
        operation.setId(9L); operation.setOperationKey("op"); operation.setAttemptCount(2);
        when(operations.listStaleRunning(100)).thenReturn(List.of(operation));
        when(center.createTask(any())).thenReturn(unified(41L,2));
        MinioclientUtil minio=mock(MinioclientUtil.class);
        CrossStoreRecoveryService service=new CrossStoreRecoveryService(operations,mock(FileInfoMapper.class),
                mock(SpaceFileMapper.class),mock(FileVersionMapper.class),minio);
        service.setAsyncTaskInfrastructure(maintenanceProperties(),center);

        service.reconcileStaleOperations();

        verify(operations).bindRecoveryTask(eq(9L),eq(2),eq(41L),any());
        verifyNoInteractions(minio);
    }

    @Test
    void ragScannerFansOutCleanupAndValidationTasksWithoutCallingQdrant() {
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        SpaceRagDocument cleanup=document(5L,"CLEANUP_PENDING",2L);
        SpaceRagDocument active=document(6L,"ACTIVE",3L); active.setIndexStatus("SUCCESS");
        when(documents.listCleanupPending(200)).thenReturn(List.of(cleanup));
        when(documents.listActiveVectorDocuments()).thenReturn(List.of(active));
        UnifiedTaskCenterService center=mock(UnifiedTaskCenterService.class);
        when(center.createTask(any())).thenReturn(unified(51L,2),unified(52L,3));
        QdrantVectorStoreService vectors=mock(QdrantVectorStoreService.class);
        RagIndexConsistencyService service=new RagIndexConsistencyService(documents,mock(SpaceRagChunkRefMapper.class),
                mock(RagIndexTransactionService.class),vectors);
        service.setAsyncTaskInfrastructure(maintenanceProperties(),center);

        service.reconcile();

        verify(documents).bindConsistencyTask(5L,2L,51L);
        verify(documents).bindConsistencyTask(6L,3L,52L);
        verifyNoInteractions(vectors);
    }

    @Test
    void validRagAsyncCheckAdvancesVersionThroughFence() {
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs=mock(SpaceRagChunkRefMapper.class);
        QdrantVectorStoreService vectors=mock(QdrantVectorStoreService.class);
        SpaceRagDocument active=document(6L,"ACTIVE",3L);
        active.setIndexStatus("SUCCESS"); active.setChunkCount(2); active.setConsistencyAsyncTaskId(52L);
        when(documents.getAnyById(6L)).thenReturn(active);
        when(refs.countActiveByDocumentId(6L)).thenReturn(2);
        when(vectors.countByFileUuidStrict("file-6")).thenReturn(2);
        when(documents.completeConsistencyValidation(6L,3L,52L)).thenReturn(1);
        RagIndexConsistencyService service=new RagIndexConsistencyService(documents,refs,
                mock(RagIndexTransactionService.class),vectors);

        service.executeValidationAsync(6L,52L,3L,mock(TaskExecutionContext.class));

        verify(documents).completeConsistencyValidation(6L,3L,52L);
    }

    private AsyncMqProperties cleanupProperties() { AsyncMqProperties value=new AsyncMqProperties(); value.setEnabled(true); value.setCleanup(true); return value; }
    private AsyncMqProperties maintenanceProperties() { AsyncMqProperties value=new AsyncMqProperties(); value.setEnabled(true); value.setMaintenance(true); return value; }
    private UnifiedAsyncTask unified(Long id,long version) { UnifiedAsyncTask value=new UnifiedAsyncTask(); value.setId(id); value.setResourceVersion(version); return value; }
    private UploadTask upload(Long id,int status) { UploadTask value=new UploadTask(); value.setId(id); value.setUserId(1L); value.setStatus(status); value.setFileUuid("file-"+id); return value; }
    private SpaceRagDocument document(Long id,String vectorState,Long version) { SpaceRagDocument value=new SpaceRagDocument(); value.setId(id); value.setSpaceId(1L); value.setSpaceFileId(id+10); value.setFileUuid("file-"+id); value.setCreatedBy(1L); value.setStatus(1); value.setVectorState(vectorState); value.setConsistencyVersion(version); value.setUpdatetime(LocalDateTime.now()); return value; }
}
