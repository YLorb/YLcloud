package com.ylcloud.service;

import com.ylcloud.async.task.RetryableTaskException;
import com.ylcloud.entity.File;
import com.ylcloud.entity.PhysicalFileCleanupTask;
import com.ylcloud.entity.Space;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.PhysicalFileCleanupTaskMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDeletionDataCleanupServiceTest {
    @Test
    void sharedPhysicalFileIsRetainedWhenAnotherReferenceExists() {
        Fixture fixture = new Fixture();
        File file = new File();
        file.setFileUuid("shared");
        when(fixture.fileInfoMapper.listAllByUserId(7L)).thenReturn(List.of(file));
        when(fixture.fileInfoMapper.softDeleteByFileUuid("shared", 7L)).thenReturn(1);
        when(fixture.fileInfoMapper.updateFileCount("shared", -1)).thenReturn(1);
        when(fixture.fileInfoMapper.getFileCount("shared")).thenReturn(2);

        Map<String, Object> result = fixture.service.releasePersonalReferences(7L);

        verify(fixture.cleanupService, never()).enqueue(any());
        assertEquals(List.of("shared"), result.get("retainedSharedFileUuids"));
    }

    @Test
    void physicalCleanupIsQueuedOnlyAfterLastReferenceIsReleased() {
        Fixture fixture = new Fixture();
        File file = new File();
        file.setFileUuid("last");
        when(fixture.fileInfoMapper.listAllByUserId(7L)).thenReturn(List.of(file));
        when(fixture.fileInfoMapper.softDeleteByFileUuid("last", 7L)).thenReturn(1);
        when(fixture.fileInfoMapper.updateFileCount("last", -1)).thenReturn(1);
        when(fixture.fileInfoMapper.getFileCount("last")).thenReturn(0);

        fixture.service.releasePersonalReferences(7L);

        verify(fixture.cleanupService).enqueue("last");
    }

    @Test
    void vectorCleanupTargetsOnlyThePersonalSpace() {
        Fixture fixture = new Fixture();
        Space personal = new Space();
        personal.setId(11L);
        when(fixture.spaceMapper.getActivePersonalByOwnerId(7L)).thenReturn(personal);

        Map<String, Object> result = fixture.service.releasePersonalReferences(7L);
        fixture.service.cleanupPersonalVectors(((Number) result.get("personalSpaceId")).longValue());

        verify(fixture.vectorStore).deleteBySpaceStrict(11L);
        verify(fixture.vectorStore, never()).deleteBySpace(any());
    }

    @Test
    void purgeCannotFinishWhilePhysicalCleanupIsPending() {
        Fixture fixture = new Fixture();
        PhysicalFileCleanupTask task = new PhysicalFileCleanupTask();
        task.setTaskStatus("PENDING");
        when(fixture.cleanupTaskMapper.getByFileUuid("pending")).thenReturn(task);

        assertThrows(RetryableTaskException.class,
                () -> fixture.service.verifyPhysicalCleanup(List.of("pending")));
    }

    private static class Fixture {
        final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
        final SpaceMapper spaceMapper = mock(SpaceMapper.class);
        final SpaceFileMapper spaceFileMapper = mock(SpaceFileMapper.class);
        final PhysicalFileCleanupService cleanupService = mock(PhysicalFileCleanupService.class);
        final PhysicalFileCleanupTaskMapper cleanupTaskMapper = mock(PhysicalFileCleanupTaskMapper.class);
        final QdrantVectorStoreService vectorStore = mock(QdrantVectorStoreService.class);
        final SpaceMemberMapper spaceMemberMapper = mock(SpaceMemberMapper.class);
        final SpaceRagChunkRefMapper chunkRefMapper = mock(SpaceRagChunkRefMapper.class);
        final SpaceRagDocumentMapper documentMapper = mock(SpaceRagDocumentMapper.class);
        final AccountDeletionDataCleanupService service = new AccountDeletionDataCleanupService(
                fileInfoMapper, spaceMapper, spaceFileMapper, cleanupService, cleanupTaskMapper, vectorStore,
                spaceMemberMapper, chunkRefMapper, documentMapper);

        Fixture() {
            when(fileInfoMapper.listAllByUserId(any())).thenReturn(List.of());
            when(spaceFileMapper.listAll(any())).thenReturn(List.of());
        }
    }
}
