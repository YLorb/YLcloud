package com.ylcloud.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkEvidenceMetadataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void addsIdSinglePageAndExactParsedTextOffsets() throws Exception {
        String json = ChunkEvidenceMetadata.enrich(
                "{\"pageStart\":12,\"pageEnd\":12,\"parser\":\"tika\"}",
                42L,"住宿费上限","前文\n住宿费上限\n后文");
        JsonNode node = mapper.readTree(json);
        assertEquals(42L,node.get("chunkId").longValue());
        assertEquals(42L,node.get("chunk_id").longValue());
        assertEquals(12,node.get("page").intValue());
        assertEquals(3,node.get("offsetStart").intValue());
        assertEquals(8,node.get("offsetEnd").intValue());
        assertEquals("tika",node.get("parser").textValue());
    }

    @Test
    void leavesUnknownLocationsNullRatherThanGuessing() throws Exception {
        String json = ChunkEvidenceMetadata.enrich(
                "{\"pageStart\":2,\"pageEnd\":3}",
                7L,"Document path: A\n\n别的正文","正文");
        JsonNode node = mapper.readTree(json);
        assertEquals(7L,node.get("chunkId").longValue());
        assertNull(ChunkEvidenceMetadata.location(json).page());
        assertNull(ChunkEvidenceMetadata.location(json).offsetStart());
        assertNull(ChunkEvidenceMetadata.location(json).offsetEnd());
    }

    @Test
    void repeatedTextHasNoUnambiguousOffset() {
        String json = ChunkEvidenceMetadata.enrich("{}",11L,"相同","相同和相同");
        assertNull(ChunkEvidenceMetadata.location(json).offsetStart());
    }

    @Test
    void locatesHeadingDecoratedBodyWithoutClaimingThePrefixIsInTheSource() {
        String json = ChunkEvidenceMetadata.enrich("{\"pageStart\":4,\"pageEnd\":4}",
                9L,"Document path: Rules\n\n正文内容","引言\n正文内容\n结尾");
        ChunkEvidenceMetadata.Location location = ChunkEvidenceMetadata.location(json);
        assertEquals(4,location.page());
        assertEquals(3,location.offsetStart());
        assertEquals(7,location.offsetEnd());
    }
}
