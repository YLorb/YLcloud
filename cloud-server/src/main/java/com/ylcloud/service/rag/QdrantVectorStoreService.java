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

    public void upsertSpaceChunks(Long spaceId, Long spaceFileId, Long documentId, List<FileRagChunk> chunks) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || chunks == null || chunks.isEmpty()) {
            return;
        }
        deleteBySpaceFile(spaceId,spaceFileId);
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

    public void deleteBySpaceStrict(Long spaceId) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled()) || spaceId == null) {
            return;
        }
        embeddingStore.removeAll(new IsEqualTo(SPACE_ID,spaceId));
    }

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

    private Filter activeSpaceFilter(Long spaceId) {
        return new And(new IsEqualTo(SPACE_ID,spaceId),new IsEqualTo(STATUS,StatusConstant.ENABLE));
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

    private String vectorId(Long spaceId, Long chunkId) {
        return UUID.nameUUIDFromBytes(("space-" + spaceId + "-chunk-" + chunkId).getBytes()).toString();
    }
}
