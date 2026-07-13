package com.ylcloud.service.knowledge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class KnowledgeSourceSnapshotServiceTest {
    private final KnowledgeSourceSnapshotService service = new KnowledgeSourceSnapshotService(new ObjectMapper());

    @Test
    void signatureIsStableAcrossDatabaseIdsAndMetadataPropertyOrder() {
        KnowledgeSourceSnapshot first = service.create(List.of(
                chunk(11L,1,"alpha","hash-alpha","{\"page\":1,\"kind\":\"text\"}"),
                chunk(12L,2,"beta","hash-beta","{\"kind\":\"text\",\"page\":2}")),100,"structured-v2");
        KnowledgeSourceSnapshot second = service.create(List.of(
                chunk(99L,2,"beta","hash-beta","{\"page\":2,\"kind\":\"text\"}"),
                chunk(98L,1,"alpha","hash-alpha","{\"kind\":\"text\",\"page\":1}")),100,"structured-v2");

        assertEquals(first.signature(),second.signature());
        assertNotEquals(first.sourceChunkIds(),second.sourceChunkIds());
    }

    @Test
    void signatureChangesWithContentMetadataOrParserVersion() {
        FileRagChunk base = chunk(11L,1,"alpha","hash-alpha","{\"page\":1}");
        String signature = service.create(List.of(base),100,"structured-v2").signature();

        assertNotEquals(signature,service.create(List.of(
                chunk(11L,1,"changed","hash-changed","{\"page\":1}")),100,"structured-v2").signature());
        assertNotEquals(signature,service.create(List.of(
                chunk(11L,1,"alpha","hash-alpha","{\"page\":2}")),100,"structured-v2").signature());
        assertNotEquals(signature,service.create(List.of(base),100,"structured-v3").signature());
    }

    private FileRagChunk chunk(Long id, int index, String content, String contentHash, String metadata) {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(id);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setContentHash(contentHash);
        chunk.setMetadata(metadata);
        return chunk;
    }
}
