package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.service.rag.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class UserMemoryRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryRetrievalService.class);
    private final UserMemoryService memoryService;
    private final UserMemoryVectorStoreService vectorStore;
    private final UserMemoryItemMapper mapper;
    private final RagModelClient modelClient;
    private final RagProperties properties;

    public UserMemoryRetrievalService(UserMemoryService memoryService, UserMemoryVectorStoreService vectorStore,
                                      UserMemoryItemMapper mapper, RagModelClient modelClient, RagProperties properties) {
        this.memoryService = memoryService; this.vectorStore = vectorStore; this.mapper = mapper;
        this.modelClient = modelClient; this.properties = properties;
    }

    public List<UserMemoryItem> retrieve(Long userId, String query) {
        if(!memoryService.enabled(userId) || query == null || query.isBlank()) return List.of();
        try {
            int candidates = positive(properties.getMemory().getCandidateTopK(),12);
            List<UserMemoryVectorStoreService.MemoryVectorHit> hits = vectorStore.search(userId,query,candidates);
            List<Long> ids = hits.stream().map(UserMemoryVectorStoreService.MemoryVectorHit::memoryId).distinct().toList();
            if(ids.isEmpty()) return List.of();
            Map<Long,UserMemoryItem> active = new HashMap<>();
            mapper.listActiveByIds(userId,ids).forEach(item -> active.put(item.getId(),item));
            List<UserMemoryItem> ordered = ids.stream().map(active::get).filter(Objects::nonNull).toList();
            if(ordered.isEmpty()) return List.of();
            int topK = Math.min(positive(properties.getMemory().getTopK(),5),ordered.size());
            List<RerankResult> reranked = modelClient.rerank(query,ordered.stream().map(UserMemoryItem::getContent).toList(),topK);
            double threshold = properties.getMemory().getMinScore() == null ? 0.25 : properties.getMemory().getMinScore();
            List<UserMemoryItem> result = new ArrayList<>();
            reranked.stream().filter(r -> r.getIndex() != null && r.getIndex() >= 0 && r.getIndex() < ordered.size())
                    .filter(r -> r.getScore() == null || r.getScore() >= threshold)
                    .sorted(Comparator.comparing(RerankResult::getScore,Comparator.nullsLast(Double::compareTo)).reversed())
                    .limit(topK).forEach(r -> result.add(ordered.get(r.getIndex())));
            return result.isEmpty() ? ordered.subList(0,topK) : result;
        } catch(Exception ex) {
            log.warn("User memory retrieval failed closed for userId={}",userId,ex);
            return List.of();
        }
    }

    private int positive(Integer value, int fallback) { return value == null || value <= 0 ? fallback : value; }
}
