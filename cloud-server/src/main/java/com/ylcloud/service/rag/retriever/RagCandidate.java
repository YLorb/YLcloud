package com.ylcloud.service.rag.retriever;

import com.ylcloud.entity.FileRagChunk;
import lombok.Data;

import java.util.LinkedHashSet;
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
    private Set<String> hitSources = new LinkedHashSet<>();

    public RagCandidate(FileRagChunk chunk) {
        this.chunk = chunk;
    }

    public void addScore(String source, double score) {
        hitSources.add(source);
        if("vector".equals(source)) {
            vectorScore = Math.max(vectorScore,score);
        } else if("keyword".equals(source)) {
            keywordScore = Math.max(keywordScore,score);
        } else if("metadata".equals(source)) {
            metadataScore = Math.max(metadataScore,score);
        } else if("title".equals(source)) {
            titleScore = Math.max(titleScore,score);
        } else if("structure".equals(source)) {
            structureScore = Math.max(structureScore,score);
        } else if("expanded".equals(source) || "hyde".equals(source) || "stepback".equals(source)) {
            expansionScore = Math.max(expansionScore,score);
        }
        finalScore = vectorScore * 0.45 + keywordScore * 0.25 + metadataScore * 0.12
                + titleScore * 0.10 + structureScore * 0.05 + expansionScore * 0.08
                + Math.max(0,hitSources.size() - 1) * 0.04;
    }
}
