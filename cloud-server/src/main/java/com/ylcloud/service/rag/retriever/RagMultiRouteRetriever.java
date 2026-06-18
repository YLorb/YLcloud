package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        if(spaceChunks == null || spaceChunks.isEmpty() || question == null || question.isBlank()) {
            return List.of();
        }
        int vectorLimit = Math.max(finalTopK,ragProperties.getVectorTopN() == null ? 30 : ragProperties.getVectorTopN());
        int keywordLimit = Math.max(finalTopK * 2,20);
        int metadataLimit = Math.max(finalTopK * 2,20);
        List<RagCandidate> candidates = new ArrayList<>();
        candidates.addAll(candidateMerger.fromChunks(
                qdrantVectorStoreService.search(spaceId,question,spaceChunks,vectorLimit,minScore),
                "vector",
                1.0
        ));
        for(String keyword : keywords(question)) {
            candidates.addAll(candidateMerger.fromChunks(
                    fileRagChunkMapper.searchBySpaceAndKeyword(spaceId,keyword,keywordLimit),
                    "keyword",
                    0.8
            ));
            candidates.addAll(candidateMerger.fromChunks(
                    fileRagChunkMapper.searchBySpaceAndMetadata(spaceId,keyword,metadataLimit),
                    "metadata",
                    0.7
            ));
        }
        return candidateMerger.merge(candidates,Math.max(vectorLimit,finalTopK * 6));
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
