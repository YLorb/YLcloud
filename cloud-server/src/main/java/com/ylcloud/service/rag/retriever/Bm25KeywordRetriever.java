package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class Bm25KeywordRetriever {
    private final RagProperties ragProperties;
    private final RagKeywordTokenizer tokenizer;

    public Bm25KeywordRetriever(RagProperties ragProperties, RagKeywordTokenizer tokenizer) {
        this.ragProperties = ragProperties;
        this.tokenizer = tokenizer;
    }

    public List<RagCandidate> retrieve(String query, List<FileRagChunk> chunks, int limit, String source, double routeWeight) {
        if(query == null || query.isBlank() || chunks == null || chunks.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> queryTokens = tokenizer.tokenize(query);
        if(queryTokens.isEmpty()) {
            return List.of();
        }
        List<DocumentTerms> documents = buildDocuments(chunks);
        if(documents.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> documentFrequency = documentFrequency(documents);
        double avgDocumentLength = documents.stream().mapToInt(DocumentTerms::length).average().orElse(1.0);
        RagProperties.Retrieval retrieval = retrievalProperties();
        double k1 = safeDouble(retrieval.getBm25K1(),1.5);
        double b = safeDouble(retrieval.getBm25B(),0.75);
        double exactBoost = safeDouble(retrieval.getExactMatchBoost(),1.5);
        String queryLower = query.toLowerCase(Locale.ROOT);
        List<RagCandidate> scored = new ArrayList<>();
        for(DocumentTerms document : documents) {
            double score = bm25Score(queryTokens,document,documentFrequency,documents.size(),avgDocumentLength,k1,b);
            score += exactScore(queryLower,queryTokens,document.rawTextLower,exactBoost);
            if(score <= 0) {
                continue;
            }
            RagCandidate candidate = new RagCandidate(document.chunk);
            candidate.addScore(source,score * routeWeight);
            scored.add(candidate);
        }
        scored.sort((left,right) -> Double.compare(right.getFinalScore(),left.getFinalScore()));
        return scored.size() > limit ? scored.subList(0,limit) : scored;
    }

    public List<String> tokenize(String text) {
        return tokenizer.tokenize(text);
    }

    private List<DocumentTerms> buildDocuments(List<FileRagChunk> chunks) {
        List<DocumentTerms> documents = new ArrayList<>();
        for(FileRagChunk chunk : chunks) {
            if(chunk == null || chunk.getId() == null) {
                continue;
            }
            String raw = ((chunk.getContent() == null ? "" : chunk.getContent()) + "\n"
                    + (chunk.getMetadata() == null ? "" : chunk.getMetadata())).trim();
            List<String> terms = tokenizer.tokenize(raw);
            if(terms.isEmpty()) {
                continue;
            }
            documents.add(new DocumentTerms(chunk,raw.toLowerCase(Locale.ROOT),termFrequency(terms),terms.size()));
        }
        return documents;
    }

    private Map<String, Integer> documentFrequency(List<DocumentTerms> documents) {
        Map<String, Integer> frequency = new HashMap<>();
        for(DocumentTerms document : documents) {
            Set<String> uniqueTerms = new HashSet<>(document.termFrequency.keySet());
            for(String term : uniqueTerms) {
                frequency.merge(term,1,Integer::sum);
            }
        }
        return frequency;
    }

    private Map<String, Integer> termFrequency(List<String> terms) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for(String term : terms) {
            frequency.merge(term,1,Integer::sum);
        }
        return frequency;
    }

    private double bm25Score(List<String> queryTokens, DocumentTerms document, Map<String, Integer> documentFrequency,
                             int documentCount, double avgDocumentLength, double k1, double b) {
        double score = 0.0;
        Set<String> uniqueQueryTokens = new LinkedHashSet<>(queryTokens);
        for(String token : uniqueQueryTokens) {
            Integer termFrequency = document.termFrequency.get(token);
            if(termFrequency == null || termFrequency == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(token,0);
            double idf = Math.log(1.0 + (documentCount - df + 0.5) / (df + 0.5));
            double denominator = termFrequency + k1 * (1.0 - b + b * document.length / Math.max(avgDocumentLength,1.0));
            score += idf * (termFrequency * (k1 + 1.0)) / denominator;
        }
        return score;
    }

    private double exactScore(String queryLower, List<String> queryTokens, String rawTextLower, double exactBoost) {
        double score = 0.0;
        if(!queryLower.isBlank() && rawTextLower.contains(queryLower)) {
            score += exactBoost;
        }
        for(String token : queryTokens) {
            if(token.length() >= 3 && rawTextLower.contains(token.toLowerCase(Locale.ROOT))) {
                score += exactBoost * 0.2;
            }
        }
        return score;
    }

    private double safeDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private RagProperties.Retrieval retrievalProperties() {
        return ragProperties.getRetrieval() == null ? new RagProperties.Retrieval() : ragProperties.getRetrieval();
    }

    private record DocumentTerms(FileRagChunk chunk, String rawTextLower, Map<String, Integer> termFrequency, int length) {
    }
}
