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
    private Chunking chunking = new Chunking();
    private Query query = new Query();
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class ModelService {
        private String baseUrl = "http://127.0.0.1:8001";
        private String embeddingModel = "BAAI/bge-m3";
        private String rerankModel = "BAAI/bge-reranker-v2-m3";
        private String chatModel = "deepseek-chat";
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
    public static class Chunking {
        private String strategy = "hybrid";
        private Boolean semanticEnabled = true;
        private Integer semanticWindowChars = 260;
        private Double semanticBreakThreshold = 0.28;
        private Boolean keepCodeBlocks = true;
    }

    @Data
    public static class Query {
        private Boolean rewriteEnabled = true;
        private Boolean modelRewriteEnabled = true;
        private Boolean multiQueryEnabled = true;
        private Boolean hydeEnabled = true;
        private Boolean stepBackEnabled = true;
        private Integer maxExpandedQueries = 4;
        private String model = "doubao-seed-2-0-pro-260215";
    }

    @Data
    public static class Retrieval {
        private Double denseWeight = 0.45;
        private Double keywordWeight = 0.25;
        private Double metadataWeight = 0.12;
        private Double titleWeight = 0.10;
        private Double structureWeight = 0.05;
        private Double queryExpansionWeight = 0.70;
        private Integer neighborWindow = 1;
    }

    @Data
    public static class Extraction {
        private Boolean enabled = true;
        private Boolean structuredEnabled = true;
        private Boolean layoutEnabled = true;
        private Boolean ocrEnabled = true;
        private Boolean vlmEnabled = false;
        private String parserVersion = "structured-v1";
        private String parserServiceBaseUrl = "http://127.0.0.1:8002";
        private Integer minTextCharsBeforeOcr = 300;
        private Double minOcrConfidenceBeforeVlm = 0.75;
        private Integer maxVlmPages = 20;
        private Integer maxOcrPages = 100;
        private Integer parserObjectUrlTtlSeconds = 300;
        private Boolean preserveTableMarkdown = true;
        private Long maxFileSize = 52428800L;
        private Integer maxTextLength = 500000;
        private Boolean fallbackToMetadata = true;
        private List<String> supportedExtensions = new ArrayList<>(Arrays.asList(
                "txt","md","markdown","log","csv","json","xml","yaml","yml","properties",
                "java","js","ts","jsx","tsx","py","go","rs","c","h","cpp","hpp","cs","php","rb","sh","sql","html","css",
                "pdf","doc","docx"
        ));
    }
}
