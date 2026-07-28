package com.ylcloud.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QdrantVectorStoreServiceTest {

    @Test
    void acceptsFiniteVectorsWithExpectedCountAndDimension() {
        int dimension = QdrantVectorStoreService.validateEmbeddingBatch(
                List.of(Embedding.from(new float[]{1.0f,0.0f}),Embedding.from(new float[]{0.0f,1.0f})),2,2);

        assertEquals(2,dimension);
    }

    @Test
    void rejectsCountDimensionAndFiniteValueViolations() {
        assertThrows(IllegalStateException.class,() -> QdrantVectorStoreService.validateEmbeddingBatch(
                List.of(Embedding.from(new float[]{1.0f,0.0f})),2,2));
        assertThrows(IllegalStateException.class,() -> QdrantVectorStoreService.validateEmbeddingBatch(
                List.of(Embedding.from(new float[]{1.0f})),1,2));
        assertThrows(IllegalStateException.class,() -> QdrantVectorStoreService.validateEmbeddingBatch(
                List.of(Embedding.from(new float[]{Float.NaN,1.0f})),1,2));
    }

    @Test
    void stablePointIdIsLogicalFileAndContentScoped() {
        assertEquals("7f5033b0-dfae-3368-8975-4bd8eb456aa1",
                QdrantVectorStoreService.stablePointId(9L,42L,"abc123",0));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                QdrantVectorStoreService.stablePointId(9L,42L,"abc123",0),
                QdrantVectorStoreService.stablePointId(9L,43L,"abc123",0));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                QdrantVectorStoreService.stablePointId(9L,42L,"abc123",0),
                QdrantVectorStoreService.stablePointId(9L,42L,"def456",0));
        assertEquals(QdrantVectorStoreService.stablePointId(9L,42L,"abc123",0),
                QdrantVectorStoreService.stablePointId(9L,42L,"abc123",0));
    }
}
