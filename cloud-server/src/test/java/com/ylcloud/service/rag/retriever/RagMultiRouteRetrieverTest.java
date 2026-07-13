package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.query.QueryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagMultiRouteRetrieverTest {

    @Test
    void retrievesAcrossVectorBm25MultiQueryHydeAndStepBackRoutesWithTop20Defaults() {
        RagProperties properties = new RagProperties();
        FileRagChunkMapper mapper = mock(FileRagChunkMapper.class);
        QdrantVectorStoreService vectorStore = mock(QdrantVectorStoreService.class);
        List<FileRagChunk> chunks = List.of(
                chunk(1L,"原始问题包含 Qdrant 配置。"),
                chunk(2L,"多 Query 角度描述向量召回。"),
                chunk(3L,"Step-back 背景问题解释检索策略。"),
                chunk(4L,"HyDE 假设答案描述 RAG 多路召回。")
        );
        when(vectorStore.search(anyLong(),anyString(),anyList(),anyInt(),isNull())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            if(query.contains("HyDE")) {
                return List.of(chunks.get(3));
            }
            if(query.contains("背景")) {
                return List.of(chunks.get(2));
            }
            if(query.contains("角度")) {
                return List.of(chunks.get(1));
            }
            return List.of(chunks.get(0));
        });
        when(mapper.searchBySpaceAndKeyword(anyLong(),anyString(),anyInt())).thenReturn(List.of(chunks.get(0)));
        when(mapper.searchBySpaceAndMetadata(anyLong(),anyString(),anyInt())).thenReturn(List.of(chunks.get(1)));
        RagMultiRouteRetriever retriever = new RagMultiRouteRetriever(
                properties,
                mapper,
                vectorStore,
                new RagCandidateMerger(properties),
                new Bm25KeywordRetriever(properties,new IkRagKeywordTokenizer())
        );
        QueryPlan plan = new QueryPlan();
        plan.setOriginal("Qdrant 配置");
        plan.setRewrittenQuery("Qdrant 向量库配置");
        plan.setExpandedQueries(List.of("向量召回角度"));
        plan.setStepBackQuery("检索策略背景");
        plan.setHydeDocument("HyDE 假设答案");
        plan.setKeywords(List.of("Qdrant","配置"));

        List<FileRagChunk> result = retriever.retrieve(1L,plan,chunks,5,null);

        assertFalse(result.isEmpty());
        verify(vectorStore,times(5)).search(anyLong(),anyString(),anyList(),anyInt(),isNull());
        verify(vectorStore).search(1L,"Qdrant 配置",chunks,20,null);
        verify(vectorStore).search(1L,"向量召回角度",chunks,20,null);
        verify(vectorStore).search(1L,"检索策略背景",chunks,20,null);
        verify(vectorStore).search(1L,"HyDE 假设答案",chunks,20,null);
        verify(mapper,atLeastOnce()).searchBySpaceAndKeyword(anyLong(),anyString(),anyInt());
        verify(mapper,atLeastOnce()).searchBySpaceAndMetadata(anyLong(),anyString(),anyInt());
    }

    private FileRagChunk chunk(Long id, String content) {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(id);
        chunk.setFileUuid("file-1");
        chunk.setChunkIndex(id.intValue());
        chunk.setContent(content);
        chunk.setMetadata("{\"fileName\":\"manual.md\"}");
        return chunk;
    }
}
