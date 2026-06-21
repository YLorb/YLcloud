package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.query.QueryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagMultiRouteRetriever {
    private static final Logger log = LoggerFactory.getLogger(RagMultiRouteRetriever.class);
    private final RagProperties ragProperties;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final RagCandidateMerger candidateMerger;
    private final Bm25KeywordRetriever bm25KeywordRetriever;

    public RagMultiRouteRetriever(RagProperties ragProperties,
                                  FileRagChunkMapper fileRagChunkMapper,
                                  QdrantVectorStoreService qdrantVectorStoreService,
                                  RagCandidateMerger candidateMerger,
                                  Bm25KeywordRetriever bm25KeywordRetriever) {
        this.ragProperties = ragProperties;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
        this.candidateMerger = candidateMerger;
        this.bm25KeywordRetriever = bm25KeywordRetriever;
    }

    public List<FileRagChunk> retrieve(Long spaceId, String question, List<FileRagChunk> spaceChunks,
                                       int finalTopK, Double minScore) {
        QueryPlan plan = new QueryPlan();
        plan.setOriginal(question);
        plan.setNormalized(question);
        plan.setKeywords(keywords(question));
        return retrieve(spaceId,plan,spaceChunks,finalTopK,minScore);
    }

    public List<FileRagChunk> retrieve(Long spaceId, QueryPlan plan, List<FileRagChunk> spaceChunks,
                                       int finalTopK, Double minScore) {
        if(spaceChunks == null || spaceChunks.isEmpty() || plan == null || plan.getOriginal() == null || plan.getOriginal().isBlank()) {
            return List.of();
        }
        RagProperties.Retrieval retrieval = retrievalProperties();
        int vectorLimit = safeLimit(retrieval.getVectorTopK(),20);
        int bm25Limit = safeLimit(retrieval.getBm25TopK(),20);
        int multiQueryLimit = safeLimit(retrieval.getMultiQueryTopK(),20);
        int hydeLimit = safeLimit(retrieval.getHydeTopK(),20);
        int stepBackLimit = safeLimit(retrieval.getStepBackTopK(),20);
        int keywordLimit = safeLimit(retrieval.getKeywordFallbackTopK(),20);
        int metadataLimit = keywordLimit;
        double expansionWeight = retrieval.getQueryExpansionWeight() == null ? 0.70 : retrieval.getQueryExpansionWeight();
        List<RagCandidate> candidates = new ArrayList<>();
        List<String> retrievalQueries = plan.retrievalQueries();
        for(int i = 0; i < retrievalQueries.size(); i++) {
            String query = retrievalQueries.get(i);
            boolean originalRoute = i == 0;
            boolean stepBackRoute = plan.getStepBackQuery() != null && plan.getStepBackQuery().equals(query);
            double weight = originalRoute ? 1.0 : expansionWeight;
            int currentVectorLimit = originalRoute ? vectorLimit : stepBackRoute ? stepBackLimit : multiQueryLimit;
            int currentBm25Limit = originalRoute ? bm25Limit : stepBackRoute ? stepBackLimit : multiQueryLimit;
            String vectorSource = originalRoute ? "vector" : stepBackRoute ? "stepback_vector" : "multi_query_vector";
            String bm25Source = originalRoute ? "bm25" : stepBackRoute ? "stepback_bm25" : "multi_query_bm25";
            candidates.addAll(candidateMerger.fromChunks(
                    qdrantVectorStoreService.search(spaceId,query,spaceChunks,currentVectorLimit,minScore),
                    vectorSource,
                    weight
            ));
            candidates.addAll(bm25KeywordRetriever.retrieve(query,spaceChunks,currentBm25Limit,bm25Source,weight));
            for(String keyword : keywords(query)) {
                candidates.addAll(candidateMerger.fromChunks(
                        fileRagChunkMapper.searchBySpaceAndKeyword(spaceId,keyword,keywordLimit),
                        "keyword_fallback",
                        0.8 * weight
                ));
                candidates.addAll(candidateMerger.fromChunks(
                        fileRagChunkMapper.searchBySpaceAndMetadata(spaceId,keyword,metadataLimit),
                        "metadata",
                        0.7 * weight
                ));
            }
            log.info("RAG retrieval route finished: source={}, bm25Source={}, topK={}, queryChars={}",
                    vectorSource,bm25Source,currentVectorLimit,query.length());
        }
        for(String keyword : plan.getKeywords()) {
            candidates.addAll(candidateMerger.fromChunks(
                    fileRagChunkMapper.searchBySpaceAndMetadata(spaceId,keyword,metadataLimit),
                    "title",
                    0.85
            ));
        }
        if(plan.getIntent() != null && ("code".equals(plan.getIntent()) || "table_lookup".equals(plan.getIntent()))) {
            String structuralKeyword = "code".equals(plan.getIntent()) ? "code" : "table";
            candidates.addAll(candidateMerger.fromChunks(
                    fileRagChunkMapper.searchBySpaceAndMetadata(spaceId,structuralKeyword,metadataLimit),
                    "structure",
                    0.75
            ));
        }
        if(plan.getHydeDocument() != null && !plan.getHydeDocument().isBlank()) {
            candidates.addAll(candidateMerger.fromChunks(
                    qdrantVectorStoreService.search(spaceId,plan.getHydeDocument(),spaceChunks,hydeLimit,minScore),
                    "hyde_vector",
                    0.65
            ));
        }
        int mergeLimit = Math.max(Math.max(vectorLimit,bm25Limit),finalTopK * 8);
        List<FileRagChunk> merged = candidateMerger.merge(candidates,mergeLimit);
        log.info("RAG multi-route retrieval merged: candidateCount={}, mergedCount={}, finalTopK={}",
                candidates.size(),merged.size(),finalTopK);
        return merged;
    }

    private int safeLimit(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private RagProperties.Retrieval retrievalProperties() {
        return ragProperties.getRetrieval() == null ? new RagProperties.Retrieval() : ragProperties.getRetrieval();
    }

    private List<String> keywords(String question) {
        List<String> keywords = new ArrayList<>();
        String normalized = question == null ? "" : question.trim();
        if(!normalized.isBlank()) {
            keywords.add(normalized);
        }
        for(String part : normalized.split("[\\s,;:\\uFF0C\\u3002\\uFF1B\\uFF1A\\u3001]+")) {
            String keyword = part.trim();
            if(keyword.length() >= 2 && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }
}
