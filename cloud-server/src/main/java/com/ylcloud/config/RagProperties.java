package com.ylcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ylcloud.rag")
public class RagProperties {
    private Boolean vectorEnabled = true;
    private Integer embeddingDimension = 1024;
    private Integer vectorTopN = 30;
    private Integer embeddingBatchSize = 8;
    private Integer connectTimeoutMs = 3000;
    private Integer readTimeoutMs = 60000;
    private ModelService modelService = new ModelService();
    private Qdrant qdrant = new Qdrant();
    private Rerank rerank = new Rerank();
    private Chat chat = new Chat();
    private Extraction extraction = new Extraction();

    @Data
    public static class ModelService {
        private String baseUrl = "http://127.0.0.1:8001";
        private String embeddingModel = "BAAI/bge-m3";
        private String rerankModel = "BAAI/bge-reranker-v2-m3";
        private String chatModel = "Qwen/Qwen2.5-7B-Instruct";
    }

    @Data
    public static class Qdrant {
        private Boolean initEnabled = true;
        private String host = "127.0.0.1";
        private Integer restPort = 6333;
        private Integer port = 6334;
        private Boolean useTls = false;
        private String apiKey;
        private String collectionName = "ylcloud_rag_bge_m3_v1";
        private String payloadTextKey = "text";
        private Double minScore = 0.0;
    }

    @Data
    public static class Rerank {
        private Boolean enabled = true;
        private Integer topK = 5;
    }

    @Data
    public static class Chat {
        private Boolean enabled = true;
        private Integer maxContextChars = 12000;
        private Integer maxChunkChars = 1800;
        private Integer maxAnswerTokens = 1024;
        private Double temperature = 0.2;
        private String noAnswerText = "当前知识库中没有检索到足够的依据，无法回答该问题。";
        private String unavailableText = "已检索到相关资料，但问答模型暂不可用。请先查看下方引用内容，稍后重试。";
        private String systemPrompt = "你是云端知识库问答助手。只能根据提供的知识库上下文回答；如果上下文没有答案，明确说明无法从当前知识库回答。回答应简洁、准确，并在相关句子后使用引用编号。";
    }

    @Data
    public static class Extraction {
        private Boolean enabled = true;
        private Long maxFileSize = 52428800L;
        private Integer maxTextLength = 500000;
        private Boolean fallbackToMetadata = true;
        private List<String> supportedExtensions = new ArrayList<>(Arrays.asList(
                "txt","md","markdown","pdf","doc","docx"
        ));
    }
}
