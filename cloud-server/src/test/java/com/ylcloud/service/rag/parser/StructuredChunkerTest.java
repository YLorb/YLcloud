package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredChunkerTest {

    @Test
    void keepsHeadingContextInChunkContentAndMetadata() {
        RagProperties properties = new RagProperties();
        StructuredChunker chunker = new StructuredChunker(new ObjectMapper(),properties);
        ParsedDocument document = ParsedDocument.success(
                "file-1",
                "hash-1",
                "tika-structured",
                "structured-v1",
                "Background\n\nThe system uses a split frontend and backend architecture.",
                List.of(
                        new DocumentBlock(0,"heading","Background",1,1,List.of("Background"),null,1.0,"tika"),
                        new DocumentBlock(1,"paragraph","The system uses a split frontend and backend architecture.",1,null,List.of("Background"),null,1.0,"tika")
                )
        );

        List<StructuredChunk> chunks = chunker.chunk(document,1000,100);

        assertEquals(1,chunks.size());
        assertTrue(chunks.get(0).getContent().contains("Document path: Background"));
        assertTrue(chunks.get(0).getMetadataJson().contains("\"headingPath\":[\"Background\"]"));
        assertTrue(chunks.get(0).getMetadataJson().contains("\"vlmEnabled\":false"));
    }

    @Test
    void marksVlmUsageWhenSourceComesFromVlmAndFeatureEnabled() {
        RagProperties properties = new RagProperties();
        properties.getExtraction().setVlmEnabled(true);
        StructuredChunker chunker = new StructuredChunker(new ObjectMapper(),properties);
        ParsedDocument document = ParsedDocument.success(
                "file-1",
                "hash-1",
                "vlm-page",
                "structured-v1",
                "Screenshot content",
                List.of(new DocumentBlock(0,"paragraph","Screenshot content",1,null,List.of(),null,0.9,"vlm-page"))
        );

        List<StructuredChunk> chunks = chunker.chunk(document,1000,100);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).getMetadataJson().contains("\"vlmEnabled\":true"));
        assertTrue(chunks.get(0).getMetadataJson().contains("\"vlmUsed\":true"));
    }

    @Test
    void keepsSmallTableAsSingleChunk() {
        RagProperties properties = new RagProperties();
        StructuredChunker chunker = new StructuredChunker(new ObjectMapper(),properties);
        String table = "| Name | Value |\n|---|---|\n| A | 1 |\n| B | 2 |";
        ParsedDocument document = ParsedDocument.success(
                "file-1",
                "hash-1",
                "tika-structured",
                "structured-v1",
                table,
                List.of(new DocumentBlock(0,"table",table,2,null,List.of("Metrics"),null,1.0,"docx-table"))
        );

        List<StructuredChunk> chunks = chunker.chunk(document,1000,50);

        assertEquals(1,chunks.size());
        assertEquals(table,chunks.get(0).getContent());
        assertTrue(chunks.get(0).getMetadataJson().contains("\"blockTypes\":[\"table\"]"));
        assertTrue(chunks.get(0).getMetadataJson().contains("\"tablePartCount\":1"));
    }

    @Test
    void splitsLargeMarkdownTableWithHeaderRepeated() {
        RagProperties properties = new RagProperties();
        StructuredChunker chunker = new StructuredChunker(new ObjectMapper(),properties);
        String table = "| Name | Value |\n|---|---|\n| Alpha | 1111111111 |\n| Beta | 2222222222 |\n| Gamma | 3333333333 |";
        ParsedDocument document = ParsedDocument.success(
                "file-1",
                "hash-1",
                "tika-structured",
                "structured-v1",
                table,
                List.of(new DocumentBlock(3,"table",table,2,null,List.of("Metrics"),null,1.0,"docx-table"))
        );

        List<StructuredChunk> chunks = chunker.chunk(document,60,0);

        assertTrue(chunks.size() > 1);
        for(StructuredChunk chunk : chunks) {
            assertTrue(chunk.getContent().startsWith("| Name | Value |\n|---|---|"));
        }
        assertTrue(chunks.get(0).getMetadataJson().contains("\"preserveTableHeader\":true"));
    }
}
