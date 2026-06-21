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

        StructuredChunk child = firstChild(chunks);

        assertEquals(2,chunks.size());
        assertTrue(chunks.get(0).getMetadataJson().contains("\"chunkType\":\"parent\""));
        assertTrue(child.getContent().contains("Document path: Background"));
        assertTrue(child.getMetadataJson().contains("\"headingPath\":[\"Background\"]"));
        assertTrue(child.getMetadataJson().contains("\"parentChunkIndex\":0"));
        assertTrue(child.getMetadataJson().contains("\"vlmEnabled\":false"));
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

        StructuredChunk child = firstChild(chunks);

        assertFalse(chunks.isEmpty());
        assertTrue(child.getMetadataJson().contains("\"vlmEnabled\":true"));
        assertTrue(child.getMetadataJson().contains("\"vlmUsed\":true"));
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

        StructuredChunk child = firstChild(chunks);

        assertEquals(2,chunks.size());
        assertEquals(table,child.getContent());
        assertTrue(child.getMetadataJson().contains("\"blockTypes\":[\"table\"]"));
        assertTrue(child.getMetadataJson().contains("\"tablePartCount\":1"));
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

        List<StructuredChunk> children = childChunks(chunks);

        assertTrue(children.size() > 1);
        for(StructuredChunk chunk : children) {
            assertTrue(chunk.getContent().startsWith("| Name | Value |\n|---|---|"));
        }
        assertTrue(children.get(0).getMetadataJson().contains("\"preserveTableHeader\":true"));
    }

    @Test
    void fallsBackToFixedWindowWhenBlocksAreMissing() {
        RagProperties properties = new RagProperties();
        StructuredChunker chunker = new StructuredChunker(new ObjectMapper(),properties);
        String text = "A".repeat(1700);
        ParsedDocument document = ParsedDocument.success("file-1","hash-1","plain","structured-v1",text,List.of());

        List<StructuredChunk> chunks = chunker.chunk(document,1000,100);

        assertEquals(3,chunks.size());
        assertTrue(chunks.get(0).getMetadataJson().contains("\"fallbackChunking\":true"));
        assertTrue(chunks.get(0).getMetadataJson().contains("\"chunkingStrategy\":\"fixed_window\""));
        assertEquals(800,chunks.get(0).getContent().length());
        assertEquals(800,chunks.get(1).getContent().length());
        assertEquals(300,chunks.get(2).getContent().length());
    }

    private StructuredChunk firstChild(List<StructuredChunk> chunks) {
        return childChunks(chunks).get(0);
    }

    private List<StructuredChunk> childChunks(List<StructuredChunk> chunks) {
        return chunks.stream().filter(chunk -> "child".equals(chunk.getChunkType())).toList();
    }
}
