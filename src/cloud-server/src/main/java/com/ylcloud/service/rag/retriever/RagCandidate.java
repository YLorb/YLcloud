package com.ylcloud.service.rag.retriever;

import com.ylcloud.entity.FileRagChunk;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
public class RagCandidate {
    private FileRagChunk chunk;
    private double vectorScore;
    private double keywordScore;
    private double metadataScore;
    private double titleScore;
    private double structureScore;
    private double expansionScore;
    private double finalScore;
    private Map<String, Double> sourceScores = new LinkedHashMap<>();
    private Map<String, Integer> sourceRanks = new LinkedHashMap<>();
    private Set<String> hitSources = new LinkedHashSet<>();

    public RagCandidate(FileRagChunk chunk) {
        this.chunk = chunk;
    }

    public void addScore(String source, double score) {
        if(source == null || source.isBlank()) {
            return;
        }
        hitSources.add(source);
        sourceScores.merge(source,score,Math::max);
        if("vector".equals(source)) {
            vectorScore = Math.max(vectorScore,score);
        } else if("keyword".equals(source) || "keyword_fallback".equals(source)) {
            keywordScore = Math.max(keywordScore,score);
        } else if("metadata".equals(source)) {
            metadataScore = Math.max(metadataScore,score);
        } else if("title".equals(source)) {
            titleScore = Math.max(titleScore,score);
        } else if("structure".equals(source)) {
            structureScore = Math.max(structureScore,score);
        } else if("expanded".equals(source) || "hyde".equals(source) || "stepback".equals(source)
                || source.startsWith("multi_query") || source.startsWith("hyde") || source.startsWith("stepback")) {
            expansionScore = Math.max(expansionScore,score);
        }
        finalScore = sourceScores.values().stream().mapToDouble(Double::doubleValue).sum()
                + Math.max(0,hitSources.size() - 1) * 0.04;
    }

    public void addRank(String source, int rank) {
        if(source == null || source.isBlank() || rank <= 0) {
            return;
        }
        hitSources.add(source);
        sourceRanks.merge(source,rank,Math::min);
    }

    public void addRouteHit(String source, int rank, double score) {
        addScore(source,score);
        addRank(source,rank);
    }

}
