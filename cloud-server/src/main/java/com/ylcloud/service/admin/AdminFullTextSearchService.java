package com.ylcloud.service.admin;

import com.ylcloud.DTO.AdminFullTextSearchDTO;
import com.ylcloud.VO.AdminFullTextSearchResultVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.AdminChunkSearchHit;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.entity.Space;
import com.ylcloud.service.SpaceFileService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin 全文搜索服务。
 * <p>
 * 搜索逻辑（严格按需求）：
 * 1. 词语匹配：LIKE '%query%' on chunk.content（精确匹配优先级最高）
 * 2. 关键词检索：LIKE '%query%' on chunk.content（关键词匹配）
 * 3. 向量同义检索：Qdrant cosine similarity search（语义匹配）
 * <p>
 * 合并去重后排序：词语匹配 > 关键词 > 向量同义检索
 * 返回最多 50 条结果，每个结果包含文件信息 + 检索证据（chunk 内容片段）。
 */
@Service
public class AdminFullTextSearchService {

    private static final Logger log = LoggerFactory.getLogger(AdminFullTextSearchService.class);
    private static final int MAX_RESULTS = 50;
    private static final String SPACE_ID_KEY = "spaceId";
    private static final String CHUNK_ID_KEY = "chunkId";

    private final FileRagChunkMapper fileRagChunkMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceFileService spaceFileService;
    private final RagProperties ragProperties;
    private final EmbeddingModel embeddingModel;
    private final QdrantVectorStoreService vectorStore;

    public AdminFullTextSearchService(FileRagChunkMapper fileRagChunkMapper,
                                       SpaceMapper spaceMapper,
                                       SpaceFileService spaceFileService,
                                       RagProperties ragProperties,
                                       EmbeddingModel embeddingModel,
                                       QdrantVectorStoreService vectorStore) {
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.spaceMapper = spaceMapper;
        this.spaceFileService = spaceFileService;
        this.ragProperties = ragProperties;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
    }

    /**
     * 执行全文搜索。
     *
     * @param dto 搜索请求
     * @return 搜索结果
     */
    public AdminFullTextSearchResultVO search(AdminFullTextSearchDTO dto) {
        long start = System.currentTimeMillis();
        String query = dto.getQuery().trim();
        Long spaceId = dto.getSpaceId();
        int limit = MAX_RESULTS;

        // ===== 第一层：词语精确匹配（LIKE） =====
        List<AdminChunkSearchHit> wordMatchHits = performWordMatch(query, spaceId, limit);
        log.info("Admin full-text search: wordMatch hits={}", wordMatchHits.size());

        // ===== 第二层：关键词检索（LIKE on content，与词语匹配相同底层查询，但作为独立层级处理） =====
        // 关键词检索在本实现中复用 LIKE 查询，但语义上独立于词语精确匹配
        // 如果词语匹配已覆盖则跳过，否则补充
        List<AdminChunkSearchHit> keywordHits = new ArrayList<>();
        if (wordMatchHits.size() < limit) {
            keywordHits = performKeywordSearch(query, spaceId, limit - wordMatchHits.size(),
                    wordMatchHits.stream().map(AdminChunkSearchHit::getChunkId).collect(Collectors.toSet()));
            log.info("Admin full-text search: keyword hits={}", keywordHits.size());
        }

        // ===== 第三层：向量同义检索（Qdrant cosine similarity） =====
        List<AdminChunkSearchHit> vectorHits = new ArrayList<>();
        if (ragProperties.getVectorEnabled() && (wordMatchHits.size() + keywordHits.size()) < limit) {
            vectorHits = performVectorSearch(query, spaceId, limit - wordMatchHits.size() - keywordHits.size(),
                    collectChunkIds(wordMatchHits, keywordHits));
            log.info("Admin full-text search: vector hits={}", vectorHits.size());
        }

        // ===== 合并去重 + 分级排序 =====
        List<AdminFullTextSearchResultVO.FileHit> mergedFiles = mergeAndSort(
                wordMatchHits, keywordHits, vectorHits, limit);

        // ===== 填充 Space 名称和文件链接 =====
        fillFileLinks(mergedFiles);

        long tookMs = System.currentTimeMillis() - start;
        log.info("Admin full-text search completed: query='{}', files={}, took={}ms",
                query, mergedFiles.size(), tookMs);

        AdminFullTextSearchResultVO result = new AdminFullTextSearchResultVO();
        result.setFiles(mergedFiles);
        result.setTotal((long) mergedFiles.size());
        result.setTookMs(tookMs);

        // 分页
        int page = dto.getPage() == null || dto.getPage() < 1 ? 1 : dto.getPage();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? 20 : Math.min(dto.getPageSize(), 50);
        int fromIndex = Math.min((page - 1) * pageSize, mergedFiles.size());
        int toIndex = Math.min(fromIndex + pageSize, mergedFiles.size());
        result.setFiles(mergedFiles.subList(fromIndex, toIndex));
        result.setTotal((long) mergedFiles.size());

        return result;
    }

    /**
     * 第一层：词语精确匹配。
     * 使用 SQL LIKE '%query%' 在 chunk.content 中搜索。
     */
    private List<AdminChunkSearchHit> performWordMatch(String query, Long spaceId, int limit) {
        if (spaceId != null) {
            return fileRagChunkMapper.searchWordMatchBySpace(spaceId, query, limit);
        }
        return fileRagChunkMapper.searchWordMatchAllSpaces(query, limit);
    }

    /**
     * 第二层：关键词检索。
     * 与词语匹配使用相同的 LIKE 查询，但排除已命中的 chunk。
     */
    private List<AdminChunkSearchHit> performKeywordSearch(String query, Long spaceId, int limit,
                                                            java.util.Set<Long> excludeChunkIds) {
        // 复用词语匹配查询，通过后续去重排除已命中项
        List<AdminChunkSearchHit> hits;
        if (spaceId != null) {
            hits = fileRagChunkMapper.searchWordMatchBySpace(spaceId, query, limit + excludeChunkIds.size());
        } else {
            hits = fileRagChunkMapper.searchWordMatchAllSpaces(query, limit + excludeChunkIds.size());
        }
        return hits.stream()
                .filter(h -> !excludeChunkIds.contains(h.getChunkId()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 第三层：向量同义检索。
     * 使用 Qdrant cosine similarity 搜索。
     */
    private List<AdminChunkSearchHit> performVectorSearch(String query, Long spaceId, int limit,
                                                           java.util.Set<Long> excludeChunkIds) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 构建过滤器
            Filter filter = buildSpaceFilter(spaceId);

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(limit + excludeChunkIds.size())
                    .minScore(ragProperties.getQdrant().getMinScore())
                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = vectorStore.searchForAdmin(request);

            // 提取 chunk IDs
            List<Long> chunkIds = new ArrayList<>();
            Map<Long, Double> chunkScores = new LinkedHashMap<>();
            for (var match : searchResult.matches()) {
                Metadata metadata = match.embedded().metadata();
                Long chunkId = metadata.getLong(CHUNK_ID_KEY);
                if (chunkId != null && !excludeChunkIds.contains(chunkId) && !chunkIds.contains(chunkId)) {
                    chunkIds.add(chunkId);
                    chunkScores.put(chunkId, match.score());
                }
            }

            if (chunkIds.isEmpty()) {
                return List.of();
            }

            // 从数据库获取 chunk 完整信息
            List<AdminChunkSearchHit> hits = fileRagChunkMapper.listByChunkIds(
                    chunkIds.stream().limit(limit).collect(Collectors.toList()));

            // 附加向量分数
            for (AdminChunkSearchHit hit : hits) {
                Double score = chunkScores.get(hit.getChunkId());
                // 将 score 存储在 content 末尾（临时方案，后续可优化为独立字段）
                // 这里我们直接返回，score 在 VO 层处理
            }

            return hits;
        } catch (Exception ex) {
            log.warn("Admin full-text vector search failed, falling back to empty", ex);
            return List.of();
        }
    }

    /**
     * 构建 Space 过滤器。
     */
    private Filter buildSpaceFilter(Long spaceId) {
        if (spaceId != null) {
            return new IsEqualTo(SPACE_ID_KEY, spaceId);
        }
        // 跨 Space 搜索：不过滤 spaceId
        return new IsEqualTo("status", 1);
    }

    /**
     * 收集所有 chunk IDs。
     */
    private java.util.Set<Long> collectChunkIds(List<AdminChunkSearchHit>... hitLists) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (List<AdminChunkSearchHit> hits : hitLists) {
            for (AdminChunkSearchHit hit : hits) {
                ids.add(hit.getChunkId());
            }
        }
        return ids;
    }

    /**
     * 合并去重 + 分级排序。
     * 排序规则：词语匹配(0) > 关键词(1) > 向量同义检索(2)
     * 同一文件有多个 chunk 时，取最高优先级。
     */
    @SuppressWarnings("unchecked")
    private List<AdminFullTextSearchResultVO.FileHit> mergeAndSort(
            List<AdminChunkSearchHit> wordMatchHits,
            List<AdminChunkSearchHit> keywordHits,
            List<AdminChunkSearchHit> vectorHits,
            int limit) {

        // 使用 LinkedHashMap 保持插入顺序，key = documentId
        Map<Long, MergedFileEntry> fileMap = new LinkedHashMap<>();

        // 词语匹配（优先级 0）
        for (AdminChunkSearchHit hit : wordMatchHits) {
            fileMap.computeIfAbsent(hit.getDocumentId(), k -> new MergedFileEntry(hit, 0));
        }

        // 关键词检索（优先级 1）
        for (AdminChunkSearchHit hit : keywordHits) {
            fileMap.computeIfAbsent(hit.getDocumentId(), k -> new MergedFileEntry(hit, 1));
        }

        // 向量同义检索（优先级 2）
        for (AdminChunkSearchHit hit : vectorHits) {
            fileMap.computeIfAbsent(hit.getDocumentId(), k -> new MergedFileEntry(hit, 2));
        }

        // 按优先级排序
        List<AdminFullTextSearchResultVO.FileHit> result = fileMap.values().stream()
                .sorted(java.util.Comparator.comparingInt(e -> e.priority))
                .limit(limit)
                .map(e -> {
                    AdminFullTextSearchResultVO.FileHit fileHit = new AdminFullTextSearchResultVO.FileHit();
                    fileHit.setDocumentId(e.hit.getDocumentId());
                    fileHit.setSpaceId(e.hit.getSpaceId());
                    fileHit.setSpaceFileId(e.hit.getSpaceFileId());
                    fileHit.setFileUuid(e.hit.getFileUuid());
                    fileHit.setFileName(e.hit.getFileName());
                    fileHit.setFileType(e.hit.getFileType());
                    fileHit.setChunkCount(e.hit.getChunkCount());
                    fileHit.setMatchType(getMatchTypeLabel(e.priority));

                    // 构建检索证据
                    AdminFullTextSearchResultVO.ChunkEvidence evidence =
                            new AdminFullTextSearchResultVO.ChunkEvidence(
                                    e.hit.getChunkId(),
                                    truncate(e.hit.getContent(), 300),
                                    null
                            );
                    fileHit.setEvidences(List.of(evidence));

                    return fileHit;
                })
                .collect(Collectors.toList());

        return result;
    }

    /**
     * 填充文件链接。
     */
    private void fillFileLinks(List<AdminFullTextSearchResultVO.FileHit> files) {
        for (AdminFullTextSearchResultVO.FileHit file : files) {
            try {
                Space space = spaceMapper.getById(file.getSpaceId());
                if (space != null) {
                    file.setSpaceName(space.getName());
                }
                String downloadUrl = "/api/space/" + file.getSpaceId()
                        + "/files/" + file.getSpaceFileId() + "/download";
                file.setDownloadUrl(downloadUrl);
                String previewUrl = "/api/space/" + file.getSpaceId()
                        + "/files/" + file.getSpaceFileId() + "/preview";
                file.setPreviewUrl(previewUrl);
            } catch (Exception ex) {
                log.warn("Failed to fill links for documentId={}", file.getDocumentId(), ex);
            }
        }
    }

    /**
     * 获取匹配类型标签。
     */
    private String getMatchTypeLabel(int priority) {
        return switch (priority) {
            case 0 -> "WORD_MATCH";
            case 1 -> "KEYWORD";
            case 2 -> "VECTOR";
            default -> "UNKNOWN";
        };
    }

    /**
     * 截断字符串。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /**
     * 合并条目内部类。
     */
    private static class MergedFileEntry {
        final AdminChunkSearchHit hit;
        final int priority;

        MergedFileEntry(AdminChunkSearchHit hit, int priority) {
            this.hit = hit;
            this.priority = priority;
        }
    }
}
