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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class QdrantVectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreService.class);
    private static final String CHUNK_ID = "chunkId";
    private static final String SPACE_ID = "spaceId";
    private static final String SPACE_FILE_ID = "spaceFileId";
    private static final String DOCUMENT_ID = "documentId";
    private static final String FILE_UUID = "fileUuid";
    private static final String FILE_HASH = "fileHash";
    private static final String STATUS = "status";

    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;
    private final QdrantEmbeddingStore embeddingStore;
    private final RestClient restClient;

    /**
     * 初始化 QdrantVectorStoreService 对象。
     *
     * @param properties 配置属性
     * @param embeddingModel 方法入参
     */
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
        RestClient.Builder restBuilder = RestClient.builder().baseUrl(
                scheme + "://" + properties.getQdrant().getHost() + ":" + properties.getQdrant().getRestPort());
        if(properties.getQdrant().getApiKey() != null && !properties.getQdrant().getApiKey().isBlank()) {
            restBuilder.defaultHeader("api-key",properties.getQdrant().getApiKey());
        }
        this.restClient = restBuilder.build();
    }

    /**
     * 执行 upsertSpaceChunks 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param documentId 文档 ID
     * @param chunks 文件分片列表
     */
    public int upsertSpaceChunks(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled())) {
            throw new IllegalStateException("Qdrant vector indexing is disabled");
        }
        if(chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("RAG indexing requires at least one non-empty chunk");
        }
        deleteBySpaceFileStrict(spaceId,spaceFileId);
        int batchSize = properties.getEmbeddingBatchSize() == null || properties.getEmbeddingBatchSize() < 1
                ? 8 : properties.getEmbeddingBatchSize();
        int indexedCount = 0;
        for(int start = 0; start < chunks.size(); start += batchSize) {
            List<FileRagChunk> batch = chunks.subList(start,Math.min(start + batchSize,chunks.size()));
            List<String> ids = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for(FileRagChunk chunk : batch) {
                if(chunk.getId() == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    throw new IllegalArgumentException("RAG indexing received an invalid chunk");
                }
                ids.add(vectorId(spaceId,chunk.getId()));
                segments.add(TextSegment.from(chunk.getContent(),metadata(spaceId,spaceFileId,documentId,chunk)));
            }
            if(segments.isEmpty()) {
                throw new IllegalArgumentException("RAG indexing batch contains no valid chunk content");
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            validateEmbeddingBatch(embeddings,segments.size(),properties.getEmbeddingDimension());
            embeddingStore.addAll(ids,embeddings,segments);
            verifyStoredVector(spaceId,spaceFileId,batch.get(0).getId(),embeddings.get(0));
            indexedCount += embeddings.size();
        }
        if(indexedCount != chunks.size()) {
            throw new IllegalStateException("Qdrant indexed vector count does not match valid chunk count");
        }
        awaitCountBySpaceFile(spaceId,spaceFileId,chunks.size());
        return indexedCount;
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

    private void verifyStoredVector(Long spaceId, Long spaceFileId, Long chunkId, Embedding queryEmbedding) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(1)
                    .minScore(0.95)
                    .filter(new And(
                            new And(new IsEqualTo(SPACE_ID,spaceId),new IsEqualTo(SPACE_FILE_ID,spaceFileId)),
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
     * 搜索 search 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param question 问题内容
     * @param candidates 方法入参
     * @param limit 限制数量
     * @param minScore 最小分数
     * @return 列表结果
     */
    public List<FileRagChunk> search(Long spaceId, String question, List<FileRagChunk> candidates, int limit, Double minScore) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || question == null || question.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(limit)
                    .minScore(minScore == null ? properties.getQdrant().getMinScore() : minScore)
                    .filter(activeSpaceFilter(spaceId))
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<FileRagChunk> result = new ArrayList<>();
            for(EmbeddingMatch<TextSegment> match : searchResult.matches()) {
                Long chunkId = match.embedded().metadata().getLong(CHUNK_ID);
                FileRagChunk chunk = findChunk(candidates,chunkId);
                if(chunk != null && !result.contains(chunk)) {
                    result.add(chunk);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Qdrant vector search failed, falling back to DB keyword search",ex);
            return List.of();
        }
    }

    /**
     * 删除 deleteBySpaceFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     */
    public void deleteBySpaceFile(Long spaceId, Long spaceFileId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null || spaceFileId == null) {
            return;
        }
        try {
            embeddingStore.removeAll(new And(new IsEqualTo(SPACE_ID,spaceId),new IsEqualTo(SPACE_FILE_ID,spaceFileId)));
        } catch (Exception ex) {
            log.warn("Failed to remove Qdrant vectors for spaceId={}, spaceFileId={}",spaceId,spaceFileId,ex);
        }
    }

    /** 删除失败时抛出异常，供持久化任务记录失败并重试。 */
    public void deleteBySpaceFileStrict(Long spaceId, Long spaceFileId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null || spaceFileId == null) {
            return;
        }
        embeddingStore.removeAll(new And(new IsEqualTo(SPACE_ID,spaceId),new IsEqualTo(SPACE_FILE_ID,spaceFileId)));
        awaitCountBySpaceFile(spaceId,spaceFileId,0);
    }

    public int countByDocumentStrict(Long spaceId, Long documentId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null || documentId == null) {
            return 0;
        }
        return count(Map.of(SPACE_ID,spaceId,DOCUMENT_ID,documentId));
    }

    public int countBySpaceFileStrict(Long spaceId, Long spaceFileId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null || spaceFileId == null) {
            return 0;
        }
        return count(Map.of(SPACE_ID,spaceId,SPACE_FILE_ID,spaceFileId));
    }

    /**
     * 删除 deleteBySpace 相关逻辑。
     *
     * @param spaceId 空间 ID
     */
    public void deleteBySpace(Long spaceId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null) {
            return;
        }
        try {
            embeddingStore.removeAll(new IsEqualTo(SPACE_ID,spaceId));
        } catch (Exception ex) {
            log.warn("Failed to remove Qdrant vectors for spaceId={}",spaceId,ex);
        }
    }

    /**
     * 删除 deleteBySpaceStrict 相关逻辑。
     *
     * @param spaceId 空间 ID
     */
    public void deleteBySpaceStrict(Long spaceId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null) {
            return;
        }
        embeddingStore.removeAll(new IsEqualTo(SPACE_ID,spaceId));
    }

    /**
     * 执行 metadata 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param documentId 文档 ID
     * @param chunk 文件分片
     * @return 处理结果
     */
    private Metadata metadata(Long spaceId, Long spaceFileId, Long documentId, FileRagChunk chunk) {
        Metadata metadata = new Metadata();
        metadata.put(CHUNK_ID,chunk.getId());
        metadata.put(SPACE_ID,spaceId);
        metadata.put(SPACE_FILE_ID,spaceFileId);
        metadata.put(DOCUMENT_ID,documentId == null ? 0L : documentId);
        metadata.put(FILE_UUID,chunk.getFileUuid() == null ? "" : chunk.getFileUuid());
        metadata.put(FILE_HASH,chunk.getFileHash() == null ? "" : chunk.getFileHash());
        metadata.put(STATUS,StatusConstant.ENABLE);
        return metadata;
    }

    /**
     * 执行 activeSpaceFilter 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @return 处理结果
     */
    private Filter activeSpaceFilter(Long spaceId) {
        return new And(new IsEqualTo(SPACE_ID,spaceId),new IsEqualTo(STATUS,StatusConstant.ENABLE));
    }

    private void awaitCountBySpaceFile(Long spaceId, Long spaceFileId, int expectedCount) {
        int actual = -1;
        for(int attempt = 1; attempt <= 5; attempt++) {
            actual = countBySpaceFileStrict(spaceId,spaceFileId);
            if(actual == expectedCount) {
                return;
            }
            if(attempt < 5) {
                sleepForVerification(100L * attempt);
            }
        }
        throw new IllegalStateException("Qdrant point count mismatch for spaceId=" + spaceId +
                ", spaceFileId=" + spaceFileId + ": expected " + expectedCount + ", got " + actual);
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

    /**
     * 查找 findChunk 相关逻辑。
     *
     * @param chunks 文件分片列表
     * @param chunkId 方法入参
     * @return 处理结果
     */
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
     * 执行 vectorId 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param chunkId 方法入参
     * @return 处理结果
     */
    private String vectorId(Long spaceId, Long chunkId) {
        return UUID.nameUUIDFromBytes(("space-" + spaceId + "-chunk-" + chunkId).getBytes()).toString();
    }
}
