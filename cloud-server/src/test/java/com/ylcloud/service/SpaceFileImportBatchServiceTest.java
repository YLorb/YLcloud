package com.ylcloud.service;

import com.ylcloud.DTO.SpaceFileImportBatchCreateDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.VO.SpaceFileImportBatchVO;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFileImportBatch;
import com.ylcloud.entity.SpaceFileImportItem;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileImportBatchMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpaceFileImportBatchServiceTest {
    private final SpaceFileImportBatchMapper mapper = mock(SpaceFileImportBatchMapper.class);
    private final FileInfoMapper fileMapper = mock(FileInfoMapper.class);
    private final SpaceFileService spaceFiles = mock(SpaceFileService.class);
    private final SpaceFileAccessService access = mock(SpaceFileAccessService.class);
    private final SpaceFilePreflightService preflight = mock(SpaceFilePreflightService.class);
    private final MinioclientUtil minio = mock(MinioclientUtil.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final UnifiedTaskCenterService tasks = mock(UnifiedTaskCenterService.class);
    private final SpaceFileImportBatchService service = new SpaceFileImportBatchService(
            mapper,fileMapper,spaceFiles,access,preflight,minio,transactions,tasks);

    @Test
    void createOnlyFreezesSnapshotAndDispatchesAsyncTask() {
        UserFileDTO source = UserFileDTO.builder().id(5L).fileName("guide.pdf").fileUuid("file-5").Dir(0).build();
        File physical = File.builder().fileUuid("file-5").hash("sha-5").size(42L).build();
        when(fileMapper.getByFileId(5L,7L)).thenReturn(source);
        when(fileMapper.getFileByFileUuid("file-5",7L)).thenReturn(physical);
        doAnswer(invocation -> { ((SpaceFileImportBatch) invocation.getArgument(0)).setId(11L); return 1; })
                .when(mapper).insertBatch(any());
        doAnswer(invocation -> { ((SpaceFileImportItem) invocation.getArgument(0)).setId(12L); return 1; })
                .when(mapper).insertItem(any());
        UnifiedAsyncTask task = new UnifiedAsyncTask(); task.setId(99L);
        when(tasks.createTask(any())).thenReturn(task);
        SpaceFileImportBatch persisted = new SpaceFileImportBatch();
        persisted.setId(11L); persisted.setSpaceId(3L); persisted.setTargetParentId(8L);
        persisted.setFailurePolicy("ATOMIC"); persisted.setBatchStatus("PENDING"); persisted.setTotalCount(1);
        persisted.setPassedCount(0); persisted.setFailedCount(0); persisted.setImportedCount(0); persisted.setAsyncTaskId(99L);
        when(mapper.getBatch(11L)).thenReturn(persisted);
        when(mapper.listItems(11L)).thenReturn(List.of());
        SpaceFileImportBatchCreateDTO dto = new SpaceFileImportBatchCreateDTO();
        dto.setSourceNodeIds(List.of(5L)); dto.setTargetParentId(8L); dto.setFailurePolicy("ATOMIC");

        SpaceFileImportBatchVO result = service.create(3L,dto,7L);

        assertEquals("PENDING",result.getBatchStatus());
        assertEquals(99L,result.getAsyncTaskId());
        ArgumentCaptor<SpaceFileImportItem> item = ArgumentCaptor.forClass(SpaceFileImportItem.class);
        verify(mapper).insertItem(item.capture());
        assertEquals("sha-5",item.getValue().getContentHash());
        assertEquals("PENDING_PREFLIGHT",item.getValue().getItemStatus());
        verifyNoInteractions(preflight,minio);
    }

    @Test
    void successfulBatchReplayDoesNotImportAgain() {
        SpaceFileImportBatch batch = new SpaceFileImportBatch();
        batch.setId(11L); batch.setBatchStatus("SUCCESS"); batch.setImportedCount(2); batch.setFailedCount(0);
        when(mapper.getBatch(11L)).thenReturn(batch);

        assertEquals("SUCCESS",service.executeBatch(11L).get("status"));
        verifyNoInteractions(preflight,minio,spaceFiles);
    }
}
