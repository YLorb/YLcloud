package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagCandidateMerger {
    private final RagProperties ragProperties;

    public RagCandidateMerger(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public List<FileRagChunk> merge(List<RagCandidate> candidates, int limit) {
        return mergeCandidates(candidates,limit).stream().map(RagCandidate::getChunk).toList();
    }

    public List<RagCandidate> mergeCandidates(List<RagCandidate> candidates, int limit) {
        if(candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, RagCandidate> merged = new LinkedHashMap<>();
        Map<String, Double> weights = sourceWeights();
        double bonus = multiRouteBonus();
        for(RagCandidate candidate : candidates) {
            if(candidate == null || candidate.getChunk() == null || candidate.getChunk().getId() == null) {
                continue;
            }
            RagCandidate current = merged.computeIfAbsent(candidate.getChunk().getId(), id -> new RagCandidate(candidate.getChunk()));
            for(Map.Entry<String, Double> entry : candidate.getSourceScores().entrySet()) {
                current.addScore(entry.getKey(),entry.getValue());
            }
            current.recalculateFinalScore(weights,bonus);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(RagCandidate::getFinalScore).reversed())
                .limit(limit)
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

    private Map<String, Double> sourceWeights() {
        RagProperties.Retrieval retrieval = retrievalProperties();
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("vector",safe(retrieval.getDenseWeight(),0.45));
        weights.put("bm25",safe(retrieval.getBm25Weight(),0.30));
        weights.put("keyword_fallback",safe(retrieval.getKeywordWeight(),0.25));
        weights.put("metadata",safe(retrieval.getMetadataWeight(),0.12));
        weights.put("title",safe(retrieval.getTitleWeight(),0.10));
        weights.put("structure",safe(retrieval.getStructureWeight(),0.05));
        weights.put("multi_query",safe(retrieval.getQueryExpansionWeight(),0.70));
        weights.put("hyde",safe(retrieval.getHydeWeight(),0.20));
        weights.put("stepback",safe(retrieval.getStepBackWeight(),0.18));
        return weights;
    }

    private double multiRouteBonus() {
        return safe(retrievalProperties().getMultiRouteBonus(),0.04);
    }

    private double safe(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private RagProperties.Retrieval retrievalProperties() {
        return ragProperties.getRetrieval() == null ? new RagProperties.Retrieval() : ragProperties.getRetrieval();
    }
}
