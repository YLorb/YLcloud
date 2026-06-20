package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.query.QueryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RagMultiRouteRetriever {
    private final RagProperties ragProperties;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final RagCandidateMerger candidateMerger;

    public RagMultiRouteRetriever(RagProperties ragProperties,
                                  FileRagChunkMapper fileRagChunkMapper,
                                  QdrantVectorStoreService qdrantVectorStoreService,
                                  RagCandidateMerger candidateMerger) {
        this.ragProperties = ragProperties;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
        this.candidateMerger = candidateMerger;
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
        int vectorLimit = Math.max(finalTopK,ragProperties.getVectorTopN() == null ? 30 : ragProperties.getVectorTopN());
        int keywordLimit = Math.max(finalTopK * 3,24);
        int metadataLimit = Math.max(finalTopK * 3,24);
        double expansionWeight = ragProperties.getRetrieval() == null || ragProperties.getRetrieval().getQueryExpansionWeight() == null
                ? 0.70 : ragProperties.getRetrieval().getQueryExpansionWeight();
        List<RagCandidate> candidates = new ArrayList<>();
        List<String> retrievalQueries = plan.retrievalQueries();
        for(int i = 0; i < retrievalQueries.size(); i++) {
            String query = retrievalQueries.get(i);
            double weight = i == 0 ? 1.0 : expansionWeight;
            String source = i == 0 ? "vector" : "expanded";
            candidates.addAll(candidateMerger.fromChunks(
                    qdrantVectorStoreService.search(spaceId,query,spaceChunks,vectorLimit,minScore),
                    source,
                    weight
            ));
            for(String keyword : keywords(query)) {
                candidates.addAll(candidateMerger.fromChunks(
                        fileRagChunkMapper.searchBySpaceAndKeyword(spaceId,keyword,keywordLimit),
                        i == 0 ? "keyword" : "expanded",
                        0.8 * weight
                ));
                candidates.addAll(candidateMerger.fromChunks(
                        fileRagChunkMapper.searchBySpaceAndMetadata(spaceId,keyword,metadataLimit),
                        i == 0 ? "metadata" : "expanded",
                        0.7 * weight
                ));
            }
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
                    qdrantVectorStoreService.search(spaceId,plan.getHydeDocument(),spaceChunks,vectorLimit,minScore),
                    "hyde",
                    0.65
            ));
        }
        List<FileRagChunk> merged = candidateMerger.merge(candidates,Math.max(vectorLimit,finalTopK * 8));
        return expandNeighbors(merged,spaceChunks,Math.max(vectorLimit,finalTopK * 8));
    }

    private List<FileRagChunk> expandNeighbors(List<FileRagChunk> selected, List<FileRagChunk> spaceChunks, int limit) {
        int window = ragProperties.getRetrieval() == null || ragProperties.getRetrieval().getNeighborWindow() == null
                ? 1 : Math.max(0,ragProperties.getRetrieval().getNeighborWindow());
        if(window == 0 || selected == null || selected.isEmpty()) {
            return selected;
        }
        List<FileRagChunk> ordered = new ArrayList<>(spaceChunks);
        ordered.sort(Comparator.comparing(FileRagChunk::getFileUuid,Comparator.nullsLast(String::compareTo))
                .thenComparing(FileRagChunk::getChunkIndex,Comparator.nullsLast(Integer::compareTo)));
        List<FileRagChunk> result = new ArrayList<>();
        for(FileRagChunk chunk : selected) {
            addIfAbsent(result,chunk);
            for(int i = 0; i < ordered.size(); i++) {
                FileRagChunk current = ordered.get(i);
                if(current.getId() == null || !current.getId().equals(chunk.getId())) {
                    continue;
                }
                int from = Math.max(0,i - window);
                int to = Math.min(ordered.size() - 1,i + window);
                for(int j = from; j <= to; j++) {
                    FileRagChunk neighbor = ordered.get(j);
                    if(neighbor.getFileUuid() != null && neighbor.getFileUuid().equals(chunk.getFileUuid())) {
                        addIfAbsent(result,neighbor);
                    }
                }
                break;
            }
            if(result.size() >= limit) {
                break;
            }
        }
        return result.size() > limit ? result.subList(0,limit) : result;
    }

    private void addIfAbsent(List<FileRagChunk> chunks, FileRagChunk chunk) {
        if(chunk == null || chunk.getId() == null) {
            return;
        }
        for(FileRagChunk item : chunks) {
            if(chunk.getId().equals(item.getId())) {
                return;
            }
        }
        chunks.add(chunk);
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
