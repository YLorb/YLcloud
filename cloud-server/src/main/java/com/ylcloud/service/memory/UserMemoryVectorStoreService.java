package com.ylcloud.service.memory;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserMemoryVectorStoreService {
    private static final String CORPUS_TYPE = "corpusType";
    private static final String USER_ID = "userId";
    private static final String MEMORY_ID = "memoryId";
    private static final String STATUS = "status";
    private static final String USER_MEMORY = "user_memory";
    private static final String ACTIVE = "ACTIVE";

    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore store;
    private final RestClient restClient;

    public UserMemoryVectorStoreService(RagProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.store = QdrantEmbeddingStore.builder()
                .host(properties.getQdrant().getHost())
                .port(properties.getQdrant().getPort())
                .useTls(Boolean.TRUE.equals(properties.getQdrant().getUseTls()))
                .apiKey(properties.getQdrant().getApiKey())
                .collectionName(properties.getQdrant().getMemoryCollectionName())
                .payloadTextKey(properties.getQdrant().getPayloadTextKey())
                .build();
        String scheme = Boolean.TRUE.equals(properties.getQdrant().getUseTls()) ? "https" : "http";
        RestClient.Builder restBuilder = RestClient.builder().baseUrl(scheme + "://" + properties.getQdrant().getHost() + ":" + properties.getQdrant().getRestPort());
        if(properties.getQdrant().getApiKey() != null && !properties.getQdrant().getApiKey().isBlank()) restBuilder.defaultHeader("api-key",properties.getQdrant().getApiKey());
        this.restClient = restBuilder.build();
    }

    public String upsert(UserMemoryItem item) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled())) throw new IllegalStateException("User memory vector indexing is disabled");
        String pointId = pointId(item.getId());
        Metadata metadata = new Metadata();
        metadata.put(CORPUS_TYPE,USER_MEMORY);
        metadata.put(USER_ID,item.getUserId());
        metadata.put(MEMORY_ID,item.getId());
        metadata.put(STATUS,ACTIVE);
        TextSegment segment = TextSegment.from(item.getContent(),metadata);
        Embedding embedding = embeddingModel.embed(segment).content();
        if(embedding == null || embedding.vector() == null || embedding.vector().length != properties.getEmbeddingDimension()) {
            throw new IllegalStateException("User memory embedding dimension mismatch");
        }
        store.addAll(List.of(pointId),List.of(embedding),List.of(segment));
        if(searchByEmbedding(item.getUserId(),embedding,1).stream().noneMatch(hit -> item.getId().equals(hit.memoryId()))) {
            throw new IllegalStateException("User memory Qdrant write verification failed");
        }
        return pointId;
    }

    public List<MemoryVectorHit> search(Long userId, String query, int limit) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || query == null || query.isBlank()) return List.of();
        Embedding embedding = embeddingModel.embed(query).content();
        return searchByEmbedding(userId,embedding,limit);
    }

    private List<MemoryVectorHit> searchByEmbedding(Long userId, Embedding embedding, int limit) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding).maxResults(Math.max(1,limit)).minScore(0.0)
                .filter(new And(
                        new And(new IsEqualTo(CORPUS_TYPE,USER_MEMORY),new IsEqualTo(USER_ID,userId)),
                        new IsEqualTo(STATUS,ACTIVE)))
                .build();
        return store.search(request).matches().stream().map(this::toHit).toList();
    }

    public void delete(UserMemoryItem item) {
        if(item == null || item.getUserId() == null || item.getId() == null || !Boolean.TRUE.equals(properties.getVectorEnabled())) return;
        store.removeAll(new And(new IsEqualTo(USER_ID,item.getUserId()),new IsEqualTo(MEMORY_ID,item.getId())));
        for(int attempt = 1; attempt <= 3; attempt++) {
            if(count(item.getUserId(),item.getId()) == 0) return;
            if(attempt < 3) try { Thread.sleep(100L * attempt); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }
        throw new IllegalStateException("User memory Qdrant delete verification failed");
    }

    private MemoryVectorHit toHit(EmbeddingMatch<TextSegment> match) {
        return new MemoryVectorHit(match.embedded().metadata().getLong(MEMORY_ID),match.score());
    }

    private String pointId(Long memoryId) {
        return UUID.nameUUIDFromBytes(("user-memory-" + memoryId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @SuppressWarnings("unchecked")
    private int count(Long userId, Long memoryId) {
        Map<String,Object> filter = Map.of("must",List.of(
                Map.of("key",CORPUS_TYPE,"match",Map.of("value",USER_MEMORY)),
                Map.of("key",USER_ID,"match",Map.of("value",userId)),
                Map.of("key",MEMORY_ID,"match",Map.of("value",memoryId))));
        Map<String,Object> response = restClient.post()
                .uri("/collections/{collection}/points/count",properties.getQdrant().getMemoryCollectionName())
                .body(Map.of("filter",filter,"exact",true)).retrieve().body(Map.class);
        if(response == null || !(response.get("result") instanceof Map<?,?> result) || !(result.get("count") instanceof Number number)) {
            throw new IllegalStateException("User memory Qdrant count response is invalid");
        }
        return number.intValue();
    }

    public record MemoryVectorHit(Long memoryId, double score) {}
}
