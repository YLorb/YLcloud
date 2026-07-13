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

import java.util.ArrayList;
import java.util.List;
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
    }

    /**
     * 执行 upsertSpaceChunks 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param documentId 文档 ID
     * @param chunks 文件分片列表
     */
    public void upsertSpaceChunks(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || chunks == null || chunks.isEmpty()) {
            return;
        }
        deleteBySpaceFileStrict(spaceId,spaceFileId);
        int batchSize = properties.getEmbeddingBatchSize() == null || properties.getEmbeddingBatchSize() < 1
                ? 8 : properties.getEmbeddingBatchSize();
        for(int start = 0; start < chunks.size(); start += batchSize) {
            List<FileRagChunk> batch = chunks.subList(start,Math.min(start + batchSize,chunks.size()));
            List<String> ids = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for(FileRagChunk chunk : batch) {
                if(chunk.getId() == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    continue;
                }
                ids.add(vectorId(spaceId,chunk.getId()));
                segments.add(TextSegment.from(chunk.getContent(),metadata(spaceId,spaceFileId,documentId,chunk)));
            }
            if(segments.isEmpty()) {
                continue;
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            if(embeddings.size() != segments.size()) {
                throw new IllegalStateException("BGE embedding response size does not match request size");
            }
            embeddingStore.addAll(ids,embeddings,segments);
        }
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
