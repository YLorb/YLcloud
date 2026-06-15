package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RagRerankService {
    private static final Logger log = LoggerFactory.getLogger(RagRerankService.class);

    private final RagModelClient ragModelClient;
    private final RagProperties properties;

    /**
     * 初始化 RagRerankService 对象。
     *
     * @param ragModelClient RAG 模型客户端
     * @param properties 配置属性
     */
    public RagRerankService(RagModelClient ragModelClient, RagProperties properties) {
        this.ragModelClient = ragModelClient;
        this.properties = properties;
    }

    /**
     * 重排序 rerank 相关逻辑。
     *
     * @param query 查询内容
     * @param chunks 文件分片列表
     * @param finalTopK 最终召回数量
     * @return 列表结果
     */
    public List<FileRagChunk> rerank(String query, List<FileRagChunk> chunks, int finalTopK) {
        if(!Boolean.TRUE.equals(properties.getRerank().getEnabled()) || chunks == null || chunks.size() <= 1) {
            return limit(chunks,finalTopK);
        }
        try {
            List<String> documents = new ArrayList<>();
            for(FileRagChunk chunk : chunks) {
                documents.add(chunk.getContent());
            }
            List<RerankResult> results = ragModelClient.rerank(query,documents,Math.min(finalTopK,chunks.size()));
            if(results.isEmpty()) {
                return limit(chunks,finalTopK);
            }
            List<FileRagChunk> reranked = new ArrayList<>();
            results.stream()
                    .filter(result -> result.getIndex() != null && result.getIndex() >= 0 && result.getIndex() < chunks.size())
                    .sorted(Comparator.comparing(RerankResult::getScore,Comparator.nullsLast(Double::compareTo)).reversed())
                    .forEach(result -> reranked.add(chunks.get(result.getIndex())));
            return reranked.isEmpty() ? limit(chunks,finalTopK) : limit(reranked,finalTopK);
        } catch (Exception ex) {
            log.warn("BGE rerank failed, using Qdrant similarity order",ex);
            return limit(chunks,finalTopK);
        }
    }

    /**
     * 执行 limit 函数的业务处理。
     *
     * @param chunks 文件分片列表
     * @param limit 限制数量
     * @return 列表结果
     */
    private List<FileRagChunk> limit(List<FileRagChunk> chunks, int limit) {
        if(chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(chunks.subList(0,Math.min(limit,chunks.size())));
    }
}
