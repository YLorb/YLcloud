package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexTransactionServiceTest {

    @Test
    void versionedCommitRejectsLateWorkerBeforeMutatingReferences() {
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs=mock(SpaceRagChunkRefMapper.class);
        com.ylcloud.entity.SpaceRagDocument document=new com.ylcloud.entity.SpaceRagDocument();
        document.setId(7L); document.setSpaceId(1L); document.setSpaceFileId(2L); document.setConsistencyVersion(5L); document.setVectorState("BUILDING");
        when(documents.getAnyByIdForUpdate(7L)).thenReturn(document);
        RagIndexTransactionService service=new RagIndexTransactionService(documents,refs);
        assertThrows(com.ylcloud.async.task.StaleTaskException.class,()->service.commitIndexVersioned(1L,2L,7L,List.of(chunk(11L)),4L));
        org.mockito.Mockito.verifyNoInteractions(refs);
    }

    @Test
    void oldFailureCannotDisableNewerGenerationReferences() {
        SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs=mock(SpaceRagChunkRefMapper.class);
        RagIndexTransactionService service=new RagIndexTransactionService(documents,refs);
        assertFalse(service.failBuildingIndexVersioned(7L,4L,"old failure"));
        org.mockito.Mockito.verifyNoInteractions(refs);
    }

    @Test
    void beginConflictDoesNotDisableAConcurrentWritersReferences() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        when(documents.beginIndex(eq(7L),any(LocalDateTime.class))).thenReturn(0);

        RagIndexTransactionService service = new RagIndexTransactionService(documents,refs);

        assertThrows(ConflictException.class,() -> service.beginIndex(7L));
        verify(refs,never()).disableByDocumentId(any(),any());
    }

    @Test
    void commitRejectsADeletedOrSupersededDocument() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        when(refs.countActiveByDocumentId(7L)).thenReturn(1);
        when(documents.commitIndex(eq(7L),eq(1),any(LocalDateTime.class))).thenReturn(0);

        RagIndexTransactionService service = new RagIndexTransactionService(documents,refs);

        assertThrows(ConflictException.class,() -> service.commitIndex(1L,2L,7L,List.of(chunk(11L))));
    }

    @Test
    void failureCompareAndSetDoesNotDisableACompletedIndex() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        when(documents.failIfBuilding(eq(7L),eq("failed"),any(LocalDateTime.class))).thenReturn(0);

        RagIndexTransactionService service = new RagIndexTransactionService(documents,refs);

        assertFalse(service.failBuildingIndex(7L,"failed"));
        verify(refs,never()).disableByDocumentId(any(),any());
    }

    @Test
    void cleanupClaimDisablesReferencesBeforeExternalCompensation() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        when(documents.claimCleanup(eq(7L),any(LocalDateTime.class))).thenReturn(1);

        RagIndexTransactionService service = new RagIndexTransactionService(documents,refs);

        service.claimCleanup(7L);
        verify(refs).disableByDocumentId(eq(7L),any(LocalDateTime.class));
    }

    private FileRagChunk chunk(Long id) {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(id);
        chunk.setContent("content");
        return chunk;
    }
}
