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
        int rrfK = rrfK();
        for(RagCandidate candidate : candidates) {
            if(candidate == null || candidate.getChunk() == null || candidate.getChunk().getId() == null) {
                continue;
            }
            RagCandidate current = merged.computeIfAbsent(candidate.getChunk().getId(), id -> new RagCandidate(candidate.getChunk()));
            for(Map.Entry<String, Double> entry : candidate.getSourceScores().entrySet()) {
                current.addScore(entry.getKey(),entry.getValue());
            }
            for(Map.Entry<String, Integer> entry : candidate.getSourceRanks().entrySet()) {
                current.addRank(entry.getKey(),entry.getValue());
            }
            current.setFinalScore(rrfScore(current,weights,rrfK));
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
            candidate.addRouteHit(source,i + 1,baseScore);
            candidates.add(candidate);
        }
        return candidates;
    }

    private double rrfScore(RagCandidate candidate, Map<String, Double> sourceWeights, int rrfK) {
        double score = 0.0;
        for(Map.Entry<String, Integer> entry : candidate.getSourceRanks().entrySet()) {
            score += weight(sourceWeights,entry.getKey()) / (rrfK + entry.getValue());
        }
        return score;
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
        weights.put("profile_summary",0.18);
        weights.put("generated_question",0.20);
        weights.put("multi_query",safe(retrieval.getQueryExpansionWeight(),0.70));
        weights.put("hyde",safe(retrieval.getHydeWeight(),0.20));
        weights.put("stepback",safe(retrieval.getStepBackWeight(),0.18));
        return weights;
    }

    private int rrfK() {
        Integer value = retrievalProperties().getRrfK();
        return value == null || value < 1 ? 60 : value;
    }

    private double safe(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private double weight(Map<String, Double> sourceWeights, String source) {
        if(sourceWeights.containsKey(source)) {
            return sourceWeights.get(source);
        }
        if(source != null && source.startsWith("multi_query")) {
            return sourceWeights.getOrDefault("multi_query",1.0);
        }
        if(source != null && source.startsWith("hyde")) {
            return sourceWeights.getOrDefault("hyde",1.0);
        }
        if(source != null && source.startsWith("stepback")) {
            return sourceWeights.getOrDefault("stepback",1.0);
        }
        return 1.0;
    }

    private RagProperties.Retrieval retrievalProperties() {
        return ragProperties.getRetrieval() == null ? new RagProperties.Retrieval() : ragProperties.getRetrieval();
    }
}
