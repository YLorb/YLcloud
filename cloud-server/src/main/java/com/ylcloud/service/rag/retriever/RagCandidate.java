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
        }
        finalScore = vectorScore * 0.65 + keywordScore * 0.25 + metadataScore * 0.10 + Math.max(0,hitSources.size() - 1) * 0.05;
    }
}
