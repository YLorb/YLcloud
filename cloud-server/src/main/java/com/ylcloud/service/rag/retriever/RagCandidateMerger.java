package com.ylcloud.service.rag.retriever;

import com.ylcloud.entity.FileRagChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagCandidateMerger {

    public List<FileRagChunk> merge(List<RagCandidate> candidates, int limit) {
        if(candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, RagCandidate> merged = new LinkedHashMap<>();
        for(RagCandidate candidate : candidates) {
            if(candidate == null || candidate.getChunk() == null || candidate.getChunk().getId() == null) {
                continue;
            }
            RagCandidate current = merged.computeIfAbsent(candidate.getChunk().getId(), id -> new RagCandidate(candidate.getChunk()));
            for(String source : candidate.getHitSources()) {
                if("vector".equals(source)) {
                    current.addScore(source,candidate.getVectorScore());
                } else if("keyword".equals(source)) {
                    current.addScore(source,candidate.getKeywordScore());
                } else if("metadata".equals(source)) {
                    current.addScore(source,candidate.getMetadataScore());
                } else if("title".equals(source)) {
                    current.addScore(source,candidate.getTitleScore());
                } else if("structure".equals(source)) {
                    current.addScore(source,candidate.getStructureScore());
                } else if("expanded".equals(source) || "hyde".equals(source) || "stepback".equals(source)) {
                    current.addScore(source,candidate.getExpansionScore());
                }
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(RagCandidate::getFinalScore).reversed())
                .limit(limit)
                .map(RagCandidate::getChunk)
                .toList();
    }

    public List<RagCandidate> fromChunks(List<FileRagChunk> chunks, String source, double baseScore) {
        List<RagCandidate> candidates = new ArrayList<>();
        if(chunks == null) {
            return candidates;
        }
        for(int i = 0; i < chunks.size(); i++) {
            RagCandidate candidate = new RagCandidate(chunks.get(i));
            candidate.addScore(source,baseScore / (i + 1));
            candidates.add(candidate);
        }
        return candidates;
    }
}
