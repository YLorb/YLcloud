package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagCandidateMergerTest {

    @Test
    void rrfUsesRankInsteadOfRawScore() {
        RagCandidate highScoreLowerRank = new RagCandidate(chunk(1L));
        highScoreLowerRank.addRouteHit("vector",2,999.0);
        RagCandidate lowScoreHigherRank = new RagCandidate(chunk(2L));
        lowScoreHigherRank.addRouteHit("vector",1,0.1);

        List<FileRagChunk> merged = new RagCandidateMerger(new RagProperties())
                .merge(List.of(highScoreLowerRank,lowScoreHigherRank),2);

        assertEquals(2L,merged.get(0).getId());
    }

    @Test
    void rrfBoostsChunksHitByMultipleRoutes() {
        RagCandidate singleRouteFirst = new RagCandidate(chunk(1L));
        singleRouteFirst.addRouteHit("vector",1,1.0);
        RagCandidate multiRoute = new RagCandidate(chunk(2L));
        multiRoute.addRouteHit("vector",2,1.0);
        multiRoute.addRouteHit("bm25",1,1.0);

        List<FileRagChunk> merged = new RagCandidateMerger(new RagProperties())
                .merge(List.of(singleRouteFirst,multiRoute),2);

        assertEquals(2L,merged.get(0).getId());
    }

    private FileRagChunk chunk(Long id) {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(id);
        chunk.setFileUuid("file-1");
        chunk.setChunkIndex(id.intValue());
        chunk.setContent("chunk-" + id);
        return chunk;
    }
}
