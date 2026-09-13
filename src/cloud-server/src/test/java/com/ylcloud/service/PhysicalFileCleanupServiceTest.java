package com.ylcloud.service;

import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.FileRagParseResultMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.PhysicalFileCleanupTaskMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhysicalFileCleanupServiceTest {
    @Test
    void enqueueDisablesMetadataAndCreatesOutboxEvent() {
        Fixture fixture = new Fixture();

        fixture.service.enqueue("file-1");

        verify(fixture.versionMapper).disableByFileUuid("file-1");
        verify(fixture.chunkMapper).disableByFileUuid("file-1");
        verify(fixture.parseMapper).disableByFileUuid("file-1");
        verify(fixture.fileInfoMapper).delete_fileinfo_ByfileUuid("file-1");
        verify(fixture.taskMapper).enqueue(eq("file-1"),any());
        verify(fixture.publisher).publishEvent(new PhysicalFileCleanupQueuedEvent("file-1"));
    }

    @Test
    void processPurgesAllVersionsAndMarksSuccess() throws Exception {
        Fixture fixture = new Fixture();
        PhysicalFileCleanupTask task = new PhysicalFileCleanupTask();
        task.setId(8L);
        task.setFileUuid("file-1");
        task.setTaskStatus("PENDING");
        when(fixture.taskMapper.getByFileUuid("file-1")).thenReturn(task);
        when(fixture.taskMapper.markRunning(eq(8L),any())).thenReturn(1);
        when(fixture.minio.removeObjectAllVersions("file-1")).thenReturn(3);

        fixture.service.process("file-1");

        verify(fixture.minio).removeObjectAllVersions("file-1");
        verify(fixture.taskMapper).markSuccess(eq(8L),any());
    }

    private static class Fixture {
        final PhysicalFileCleanupTaskMapper taskMapper = mock(PhysicalFileCleanupTaskMapper.class);
        final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
        final FileVersionMapper versionMapper = mock(FileVersionMapper.class);
        final FileRagChunkMapper chunkMapper = mock(FileRagChunkMapper.class);
        final FileRagParseResultMapper parseMapper = mock(FileRagParseResultMapper.class);
        final MinioclientUtil minio = mock(MinioclientUtil.class);
        final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        final PhysicalFileCleanupService service = new PhysicalFileCleanupService(taskMapper,fileInfoMapper,
                versionMapper,chunkMapper,parseMapper,minio,publisher);
    }
}
