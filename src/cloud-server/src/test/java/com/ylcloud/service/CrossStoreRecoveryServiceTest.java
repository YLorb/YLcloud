package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CrossStoreRecoveryServiceTest {
    private final CrossStoreOperationService operationService = mock(CrossStoreOperationService.class);
    private final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
    private final SpaceFileMapper spaceFileMapper = mock(SpaceFileMapper.class);
    private final FileVersionMapper versionMapper = mock(FileVersionMapper.class);
    private final MinioclientUtil minio = mock(MinioclientUtil.class);
    private final CrossStoreRecoveryService service = new CrossStoreRecoveryService(
            operationService,fileInfoMapper,spaceFileMapper,versionMapper,minio);

    @Test
    void marksOperationSuccessfulWhenCommittedResultExists() {
        CrossStoreOperation operation = operation("PERSONAL_UPLOAD","file","12",null);
        when(operationService.listStaleRunning(100)).thenReturn(List.of(operation));
        when(fileInfoMapper.getByFileIdAny(12L)).thenReturn(mock(UserFileDTO.class));

        service.reconcileStaleOperations();

        verify(operationService).markSuccess("op","12");
        verifyNoInteractions(minio);
    }

    @Test
    void preciselyRemovesOrphanVersionBeforeMakingOperationRetryable() throws Exception {
        CrossStoreOperation operation = operation("VERSION_UPLOAD","file",null,"v2");
        when(operationService.listStaleRunning(100)).thenReturn(List.of(operation));

        service.reconcileStaleOperations();

        verify(minio).removeObjectVersion("file","v2");
        verify(operationService).markFailed(eq("op"),contains("compensated"));
    }

    @Test
    void removesUnreferencedReservedCopyWhenVersionIdWasNotRecorded() throws Exception {
        CrossStoreOperation operation = operation("VERSION_RESTORE","copy-uuid",null,null);
        when(operationService.listStaleRunning(100)).thenReturn(List.of(operation));
        when(fileInfoMapper.getFileInfo("copy-uuid",0L)).thenReturn(null);

        service.reconcileStaleOperations();

        verify(minio).removeObjectAllVersions("copy-uuid");
        verify(operationService).markFailed(eq("op"),contains("compensated"));
    }

    private CrossStoreOperation operation(String type, String resourceId, String resultRef, String externalRef) {
        CrossStoreOperation operation = new CrossStoreOperation();
        operation.setOperationKey("op");
        operation.setOperationType(type);
        operation.setResourceId(resourceId);
        operation.setResultRef(resultRef);
        operation.setExternalRef(externalRef);
        return operation;
    }
}
