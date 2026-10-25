package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableDocumentProvenanceTest {
    @Test void unpinnedLegacyChunksCannotBecomeTrustedByParserNameAlone() {
        FileRagChunk chunk=chunk("{\"parser\":\"tika-structured\",\"parserVersion\":\"structured-v3\"}");
        assertNull(ImmutableDocumentProvenance.sourceVersion(chunk));
    }

    @Test void pinnedMetadataCannotBeReboundToAnotherContentHash() {
        ParsedDocument document=document(List.of()); document.setSourceMinioVersionId("v1");
        FileRagChunk chunk=chunk(ImmutableDocumentProvenance.stamp("{}",document));
        assertEquals("v1",ImmutableDocumentProvenance.sourceVersion(chunk));
        chunk.setFileHash("another-hash");
        assertNull(ImmutableDocumentProvenance.sourceVersion(chunk));
    }

    @Test void everyStructuredAndFixedWindowChunkCarriesTheExactSource() {
        StructuredChunker chunker=new StructuredChunker(new ObjectMapper(),new RagProperties());
        for(List<DocumentBlock> blocks:List.of(List.<DocumentBlock>of(),List.of(
                new DocumentBlock(0,"paragraph","Document content",1,null,List.of("Heading"),null,1.0,"tika")))) {
            ParsedDocument document=document(blocks); document.setSourceMinioVersionId("v2");
            List<StructuredChunk> chunks=chunker.chunk(document,1000,100);
            assertFalse(chunks.isEmpty());
            for(StructuredChunk generated:chunks)
                assertEquals("v2",ImmutableDocumentProvenance.sourceVersion(chunk(generated.getMetadataJson())));
        }
    }

    private ParsedDocument document(List<DocumentBlock> blocks) {
        return ParsedDocument.success("file","hash","tika-structured","structured-v3","Document content",blocks);
    }
    private FileRagChunk chunk(String metadata) {
        FileRagChunk chunk=new FileRagChunk(); chunk.setFileUuid("file"); chunk.setFileHash("hash"); chunk.setMetadata(metadata); return chunk;
    }
}
