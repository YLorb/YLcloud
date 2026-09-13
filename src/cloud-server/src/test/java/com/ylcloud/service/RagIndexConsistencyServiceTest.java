package com.ylcloud.service;

import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexConsistencyServiceTest {

    @Test
    void qdrantCountMismatchIsIsolatedAndCompensated() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        RagIndexTransactionService transactions = mock(RagIndexTransactionService.class);
        QdrantVectorStoreService vectors = mock(QdrantVectorStoreService.class);
        SpaceRagDocument active = document("ACTIVE","SUCCESS");
        active.setChunkCount(2);
        SpaceRagDocument failed = document("CLEANUP_PENDING","FAILED");
        when(documents.listCleanupPending(200)).thenReturn(List.of());
        when(documents.listActiveVectorDocuments()).thenReturn(List.of(active));
        when(refs.countActiveByDocumentId(7L)).thenReturn(2);
        when(vectors.countByFileUuidStrict("file-uuid-1")).thenReturn(0);
        when(transactions.failActiveIndex(7L,"RAG Qdrant vector count mismatch: expected 2, got 0")).thenReturn(true);
        when(documents.getAnyById(7L)).thenReturn(failed);
        when(transactions.claimCleanup(7L)).thenReturn(true);

        new RagIndexConsistencyService(documents,refs,transactions,vectors).reconcile();

        verify(vectors).deleteBySpaceFileStrict(1L,2L);
        verify(transactions).completeCleanup(7L);
    }

    @Test
    void temporaryQdrantOutageDoesNotDestroyAValidDatabaseIndex() {
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        RagIndexTransactionService transactions = mock(RagIndexTransactionService.class);
        QdrantVectorStoreService vectors = mock(QdrantVectorStoreService.class);
        SpaceRagDocument active = document("ACTIVE","SUCCESS");
        active.setChunkCount(2);
        when(documents.listCleanupPending(200)).thenReturn(List.of());
        when(documents.listActiveVectorDocuments()).thenReturn(List.of(active));
        when(refs.countActiveByDocumentId(7L)).thenReturn(2);
        when(vectors.countByFileUuidStrict("file-uuid-1")).thenThrow(new IllegalStateException("unavailable"));

        new RagIndexConsistencyService(documents,refs,transactions,vectors).reconcile();

        verify(transactions,never()).failActiveIndex(7L,"RAG Qdrant vector count mismatch: expected 2, got 0");
    }

    private SpaceRagDocument document(String vectorState, String indexStatus) {
        SpaceRagDocument document = new SpaceRagDocument();
        document.setId(7L);
        document.setSpaceId(1L);
        document.setSpaceFileId(2L);
        document.setFileUuid("file-uuid-1");
        document.setVectorState(vectorState);
        document.setIndexStatus(indexStatus);
        return document;
    }
}
