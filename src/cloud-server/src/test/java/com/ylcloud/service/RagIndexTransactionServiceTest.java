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
