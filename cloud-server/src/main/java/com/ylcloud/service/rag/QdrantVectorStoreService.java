package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.FileRagChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
public class QdrantVectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreService.class);
    private static final String CHUNK_ID = "chunkId";
    private static final String FILE_UUID = "fileUuid";
    private static final String FILE_HASH = "fileHash";
    private static final String STATUS = "status";
    private static final String POINT_ID_VERSION = "pointIdVersion";

    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final RestClient restClient;

    public QdrantVectorStoreService(RagProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = QdrantEmbeddingStore.builder()
                .host(properties.getQdrant().getHost())
                .port(properties.getQdrant().getPort())
                .useTls(Boolean.TRUE.equals(properties.getQdrant().getUseTls()))
                .apiKey(properties.getQdrant().getApiKey())
                .collectionName(properties.getQdrant().getCollectionName())
                .payloadTextKey(properties.getQdrant().getPayloadTextKey())
                .build();
        String scheme = Boolean.TRUE.equals(properties.getQdrant().getUseTls()) ? "https" : "http";
        SimpleClientHttpRequestFactory requestFactory=new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        RestClient.Builder restBuilder = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(scheme + "://" + properties.getQdrant().getHost() + ":" + properties.getQdrant().getRestPort());
        if(properties.getQdrant().getApiKey() != null && !properties.getQdrant().getApiKey().isBlank()) {
            restBuilder.defaultHeader("api-key",properties.getQdrant().getApiKey());
        }
        this.restClient = restBuilder.build();
    }

    public int upsertSpaceChunks(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        int indexed=stageSpaceChunks(spaceId,spaceFileId,documentId,chunks);
        cleanupObsoleteSpaceFilePoints(chunks);
        return indexed;
    }

    public int stageSpaceChunks(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled())) {
            throw new IllegalStateException("Qdrant vector indexing is disabled");
        }
        if(chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("RAG indexing requires at least one non-empty chunk");
        }
        int batchSize = properties.getEmbeddingBatchSize() == null || properties.getEmbeddingBatchSize() < 1
                ? 8 : properties.getEmbeddingBatchSize();
        int indexedCount = 0;
        Set<String> currentPointIds = new HashSet<>();
        for(int start = 0; start < chunks.size(); start += batchSize) {
            List<FileRagChunk> batch = chunks.subList(start,Math.min(start + batchSize,chunks.size()));
            List<String> ids = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for(FileRagChunk chunk : batch) {
                if(chunk.getId() == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    throw new IllegalArgumentException("RAG indexing received an invalid chunk");
                }
                String id=stablePointId(chunk.getFileUuid(),chunk.getFileHash(),chunk.getChunkIndex());
                ids.add(id);
                currentPointIds.add(id);
                segments.add(TextSegment.from(chunk.getContent(),metadata(chunk)));
            }
            if(segments.isEmpty()) {
                throw new IllegalArgumentException("RAG indexing batch contains no valid chunk content");
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            validateEmbeddingBatch(embeddings,segments.size(),properties.getEmbeddingDimension());
            embeddingStore.addAll(ids,embeddings,segments);
            verifyStoredVector(batch.get(0).getFileUuid(),batch.get(0).getFileHash(),batch.get(0).getId(),embeddings.get(0));
            indexedCount += embeddings.size();
        }
        if(indexedCount != chunks.size()) {
            throw new IllegalStateException("Qdrant indexed vector count does not match valid chunk count");
        }
        String fileUuid = chunks.get(0).getFileUuid();
        awaitPointIdsPresent(fileUuid,currentPointIds);
        return indexedCount;
    }

    public void cleanupObsoleteSpaceFilePoints(List<FileRagChunk> chunks) {
        if(chunks==null || chunks.isEmpty()) {
            throw new IllegalArgumentException("RAG point cleanup requires the committed chunk generation");
        }
        String fileUuid = chunks.get(0).getFileUuid();
        Set<String> currentIds=new HashSet<>();
        for(FileRagChunk chunk:chunks) {
            currentIds.add(stablePointId(chunk.getFileUuid(),chunk.getFileHash(),chunk.getChunkIndex()));
        }
        removeObsoletePointIds(fileUuid,currentIds);
        awaitCountByFileUuid(fileUuid,chunks.size());
    }

    static int validateEmbeddingBatch(List<Embedding> embeddings, int expectedCount, Integer expectedDimension) {
        if(embeddings == null || embeddings.size() != expectedCount) {
            int actual = embeddings == null ? 0 : embeddings.size();
            throw new IllegalStateException("BGE embedding response size mismatch: expected " + expectedCount + ", got " + actual);
        }
        int dimension = expectedDimension == null ? 0 : expectedDimension;
        for(Embedding embedding : embeddings) {
            float[] vector = embedding == null ? null : embedding.vector();
            if(vector == null || vector.length == 0) {
                throw new IllegalStateException("BGE embedding response contains an empty vector");
            }
            if(dimension > 0 && vector.length != dimension) {
                throw new IllegalStateException("BGE embedding dimension mismatch: expected " + dimension + ", got " + vector.length);
            }
            for(float value : vector) {
                if(!Float.isFinite(value)) {
                    throw new IllegalStateException("BGE embedding response contains a non-finite value");
                }
            }
        }
        return embeddings.get(0).vector().length;
    }

    private void verifyStoredVector(String fileUuid, String fileHash, Long chunkId, Embedding queryEmbedding) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(1)
                    .minScore(0.95)
                    .filter(new And(
                            new And(new IsEqualTo(FILE_UUID,fileUuid),new IsEqualTo(FILE_HASH,fileHash)),
                            new IsEqualTo(CHUNK_ID,chunkId)))
                    .build();
        for(int attempt = 1; attempt <= 3; attempt++) {
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            Set<Long> storedChunkIds = new HashSet<>();
            for(EmbeddingMatch<TextSegment> match : result.matches()) {
                storedChunkIds.add(match.embedded().metadata().getLong(CHUNK_ID));
            }
            if(storedChunkIds.contains(chunkId)) {
                return;
            }
            if(attempt < 3) {
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Qdrant write verification was interrupted",ex);
                }
            }
        }
        throw new IllegalStateException("Qdrant write verification failed for chunk " + chunkId);
    }

    /**
     * 向量检索：按 fileUuid 白名单过滤，权限由调用方通过 candidates 保证。
     */
    public List<FileRagChunk> search(Set<String> allowedFileUuids, String question, List<FileRagChunk> candidates, int limit, Double minScore) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || question == null || question.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            Filter filter = new IsEqualTo(STATUS,StatusConstant.ENABLE);
            log.info("Qdrant search: allowedFiles={}, candidates={}, limit={}, minScore={}",
                    allowedFileUuids == null ? "all" : allowedFileUuids.size(),
                    candidates.size(), limit,
                    minScore == null ? properties.getQdrant().getMinScore() : minScore);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(limit)
                    .minScore(minScore == null ? properties.getQdrant().getMinScore() : minScore)
                    .filter(filter)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            log.info("Qdrant search result: rawMatches={}", searchResult.matches().size());
            List<FileRagChunk> result = new ArrayList<>();
            for(EmbeddingMatch<TextSegment> match : searchResult.matches()) {
                Long chunkId = match.embedded().metadata().getLong(CHUNK_ID);
                String matchFileUuid = match.embedded().metadata().getString(FILE_UUID);
                if(allowedFileUuids != null && !allowedFileUuids.contains(matchFileUuid)) {
                    continue;
                }
                FileRagChunk chunk = findChunk(candidates,chunkId);
                if(chunk != null && !result.contains(chunk)) {
                    result.add(chunk);
                }
            }
            log.info("Qdrant search filtered: resultSize={}", result.size());
            return result;
        } catch (Exception ex) {
            log.warn("Qdrant vector search failed, falling back to DB keyword search",ex);
            return List.of();
        }
    }

    public void deleteBySpaceFile(Long spaceId, Long spaceFileId) {
        log.info("deleteBySpaceFile is a no-op for shared vectors: spaceId={}, spaceFileId={}", spaceId, spaceFileId);
    }

    public void deleteBySpaceFileStrict(Long spaceId, Long spaceFileId) {
        log.info("deleteBySpaceFileStrict is a no-op for shared vectors: spaceId={}, spaceFileId={}", spaceId, spaceFileId);
    }

    public void deleteBySpace(Long spaceId) {
        log.info("deleteBySpace is a no-op for shared vectors: spaceId={}", spaceId);
    }

    public void deleteBySpaceStrict(Long spaceId) {
        log.info("deleteBySpaceStrict is a no-op for shared vectors: spaceId={}", spaceId);
    }

    public int countByDocumentStrict(Long spaceId, Long documentId) {
        return 0;
    }

    public int countBySpaceFileStrict(Long spaceId, Long spaceFileId) {
        return 0;
    }

    public int countBySpaceStrict(Long spaceId) {
        return 0;
    }

    public int countByFileUuidStrict(String fileUuid) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || fileUuid == null || fileUuid.isBlank()) {
            return 0;
        }
        return count(Map.of(FILE_UUID,fileUuid));
    }

    private Metadata metadata(FileRagChunk chunk) {
        Metadata metadata = new Metadata();
        metadata.put(CHUNK_ID,chunk.getId());
        metadata.put(FILE_UUID,chunk.getFileUuid() == null ? "" : chunk.getFileUuid());
        metadata.put(FILE_HASH,chunk.getFileHash() == null ? "" : chunk.getFileHash());
        metadata.put(STATUS,StatusConstant.ENABLE);
        metadata.put(POINT_ID_VERSION,"v2");
        return metadata;
    }

    private void awaitCountByFileUuid(String fileUuid, int expectedCount) {
        int actual = -1;
        for(int attempt = 1; attempt <= 5; attempt++) {
            actual = countByFileUuidStrict(fileUuid);
            if(actual == expectedCount) {
                return;
            }
            if(attempt < 5) {
                sleepForVerification(100L * attempt);
            }
        }
        throw new IllegalStateException("Qdrant point count mismatch for fileUuid=" + fileUuid +
                ": expected " + expectedCount + ", got " + actual);
    }

    @SuppressWarnings("unchecked")
    private int count(Map<String,Object> matches) {
        Map<String,Object> body = new LinkedHashMap<>();
        List<Map<String,Object>> must = new ArrayList<>();
        matches.forEach((key,value) -> must.add(Map.of("key",key,"match",Map.of("value",value))));
        body.put("filter",Map.of("must",must));
        body.put("exact",true);
        Map<String,Object> response = restClient.post()
                .uri("/collections/{collection}/points/count",properties.getQdrant().getCollectionName())
                .body(body)
                .retrieve()
                .body(Map.class);
        if(response == null || !(response.get("result") instanceof Map<?,?> result)
                || !(result.get("count") instanceof Number count)) {
            throw new IllegalStateException("Qdrant count response is invalid");
        }
        return count.intValue();
    }

    private void sleepForVerification(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant count verification was interrupted",ex);
        }
    }

    private FileRagChunk findChunk(List<FileRagChunk> chunks, Long chunkId) {
        if(chunkId == null) {
            return null;
        }
        for(FileRagChunk chunk : chunks) {
            if(chunkId.equals(chunk.getId())) {
                return chunk;
            }
        }
        return null;
    }

    /**
     * 文件级 pointId：同一文件无论被多少 Space 引用，pointId 唯一。
     */
    public static String stablePointId(String fileUuid, String fileHash, Integer chunkIndex) {
        if(fileUuid==null || fileUuid.isBlank() || fileHash==null || fileHash.isBlank() || chunkIndex==null || chunkIndex<0) {
            throw new IllegalArgumentException("Stable RAG point ID requires file UUID, hash and chunk index");
        }
        String source="point-id-v2|"+fileUuid+"|"+fileHash+"|"+chunkIndex;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String stablePointId(Long spaceId, Long spaceFileId, String fileHash, Integer chunkIndex) {
        throw new UnsupportedOperationException("Legacy stablePointId is removed; use stablePointId(fileUuid, fileHash, chunkIndex)");
    }

    private void awaitPointIdsPresent(String fileUuid, Set<String> expectedIds) {
        Set<String> actual=Set.of();
        for(int attempt=1;attempt<=5;attempt++) {
            actual=pointIdsByFileUuid(fileUuid);
            if(actual.containsAll(expectedIds)) return;
            if(attempt<5) sleepForVerification(100L*attempt);
        }
        Set<String> missing=new HashSet<>(expectedIds);
        missing.removeAll(actual);
        throw new IllegalStateException("Qdrant stable point verification failed; missing="+missing.size());
    }

    private void removeObsoletePointIds(String fileUuid, Set<String> currentIds) {
        List<String> obsolete=new ArrayList<>(pointIdsByFileUuid(fileUuid));
        obsolete.removeAll(currentIds);
        if(obsolete.isEmpty()) return;
        restClient.post()
                .uri("/collections/{collection}/points/delete?wait=true",properties.getQdrant().getCollectionName())
                .body(Map.of("points",obsolete)).retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private Set<String> pointIdsByFileUuid(String fileUuid) {
        Set<String> ids=new HashSet<>();
        Object offset=null;
        do {
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("filter",filterBody(Map.of(FILE_UUID,fileUuid)));
            body.put("limit",256);
            body.put("with_payload",false);
            body.put("with_vector",false);
            if(offset!=null) body.put("offset",offset);
            Map<String,Object> response=restClient.post()
                    .uri("/collections/{collection}/points/scroll",properties.getQdrant().getCollectionName())
                    .body(body).retrieve().body(Map.class);
            if(response==null || !(response.get("result") instanceof Map<?,?> result)) {
                throw new IllegalStateException("Qdrant point scroll response is invalid");
            }
            Object points=result.get("points");
            if(points instanceof List<?> list) {
                for(Object value:list) {
                    if(value instanceof Map<?,?> point && point.get("id")!=null) {
                        ids.add(String.valueOf(point.get("id")));
                    }
                }
            }
            offset=result.get("next_page_offset");
        } while(offset!=null);
        return ids;
    }

    private Map<String,Object> filterBody(Map<String,Object> matches) {
        List<Map<String,Object>> must=new ArrayList<>();
        matches.forEach((key,value) -> must.add(Map.of("key",key,"match",Map.of("value",value))));
        return Map.of("must",must);
    }
}
