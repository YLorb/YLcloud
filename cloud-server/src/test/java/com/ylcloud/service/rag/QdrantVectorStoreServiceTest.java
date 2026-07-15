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
}
