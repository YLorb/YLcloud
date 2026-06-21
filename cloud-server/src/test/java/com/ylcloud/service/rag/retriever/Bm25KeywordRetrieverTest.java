package com.ylcloud.service.rag.retriever;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25KeywordRetrieverTest {

    @Test
    void tokenizerKeepsChineseTermsModelsNumbersAndStandards() {
        RagKeywordTokenizer tokenizer = new IkRagKeywordTokenizer();

        List<String> tokens = tokenizer.tokenize("请检查 GB/T 35273-2020、BAAI/bge-m3、ERR-10023 和 v2.1.3");

        assertTrue(tokens.contains("GB/T 35273-2020") || tokens.contains("gb/t 35273-2020"));
        assertTrue(tokens.contains("BAAI/bge-m3") || tokens.contains("baai/bge-m3"));
        assertTrue(tokens.contains("ERR-10023") || tokens.contains("err-10023"));
        assertTrue(tokens.contains("v2.1.3"));
    }

    @Test
    void bm25PrioritizesExactProductModelAndNumber() {
        RagProperties properties = new RagProperties();
        Bm25KeywordRetriever retriever = new Bm25KeywordRetriever(properties,new IkRagKeywordTokenizer());
        FileRagChunk exact = chunk(1L,"设备型号 ERR-10023 在 2024 批次中需要执行复位流程。");
        FileRagChunk semantic = chunk(2L,"设备出现错误时需要执行通用恢复流程。");
        FileRagChunk unrelated = chunk(3L,"用户权限配置说明。");

        List<RagCandidate> hits = retriever.retrieve(
                "ERR-10023 2024",
                List.of(unrelated,semantic,exact),
                20,
                "bm25",
                1.0
        );

        assertEquals(1L,hits.get(0).getChunk().getId());
        assertTrue(hits.get(0).getFinalScore() > 0);
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
