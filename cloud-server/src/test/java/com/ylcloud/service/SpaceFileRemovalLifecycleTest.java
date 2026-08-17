package com.ylcloud.service;

import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceFileContentGuardMapper;
import com.ylcloud.mapper.SpaceFileDeleteBatchMapper;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SpaceFileRemovalLifecycleTest {
    @Test
    void removalClosesSearchBeforeReferenceCountAndPreservesSharedPhysicalFile() {
        SpaceFileMapper files = mock(SpaceFileMapper.class);
        FileInfoMapper physical = mock(FileInfoMapper.class);
        SpacePermissionService permissions = mock(SpacePermissionService.class);
        SpaceRagService rag = mock(SpaceRagService.class);
        PhysicalFileCleanupService cleanup = mock(PhysicalFileCleanupService.class);
        SpaceFileLifecycleService lifecycle = mock(SpaceFileLifecycleService.class);
        SpaceFileContentGuardMapper guards = mock(SpaceFileContentGuardMapper.class);
        SpaceFileService service = new SpaceFileService(
                files,guards,mock(SpaceFileDeleteBatchMapper.class),mock(UnifiedTaskCenterService.class),
                mock(TransactionTemplate.class),physical,mock(SpaceService.class),permissions,
                mock(SpaceFileAccessService.class),mock(SpaceFilePreflightService.class),rag,mock(MinioclientUtil.class),
                mock(SiteSettingService.class),cleanup,mock(InitialFileVersionService.class),
                mock(CrossStoreFileWriteService.class),mock(CrossStoreOperationService.class),lifecycle
        );
        SpaceFile file = new SpaceFile();
        file.setId(7L);
        file.setSpaceId(2L);
        file.setFileUuid("shared-file");
        file.setFileName("shared.txt");
        file.setParentId(1L);
        file.setDir(0);
        file.setStatus(1);
        file.setCreatetime(LocalDateTime.now());
        when(files.getById(2L,7L)).thenReturn(file);
        when(files.disable(eq(2L),eq(7L),any())).thenReturn(1);
        when(physical.updateFileCount("shared-file",-1)).thenReturn(1);
        when(physical.getFileCount("shared-file")).thenReturn(1);

        ReflectionTestUtils.invokeMethod(service,"removeIsolatedNode",2L,file,9L);

        var order = inOrder(lifecycle,files,physical,rag);
        order.verify(lifecycle).fileRemovalStarted(file);
        order.verify(files).disable(eq(2L),eq(7L),any());
        order.verify(physical).updateFileCount("shared-file",-1);
        order.verify(rag).handleFileRemoved(2L,7L,9L);
        verify(cleanup,never()).enqueue(anyString());
    }
}
