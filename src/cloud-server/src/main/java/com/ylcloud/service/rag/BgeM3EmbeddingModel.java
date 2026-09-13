package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BgeM3EmbeddingModel implements EmbeddingModel {
    private final RagModelClient ragModelClient;
    private final RagProperties properties;

    /**
     * 初始化 BgeM3EmbeddingModel 对象。
     *
     * @param ragModelClient RAG 模型客户端
     * @param properties 配置属性
     */
    public BgeM3EmbeddingModel(RagModelClient ragModelClient, RagProperties properties) {
        this.ragModelClient = ragModelClient;
        this.properties = properties;
    }

    /**
     * 生成向量 embedAll 相关逻辑。
     *
     * @param textSegments 方法入参
     * @return 处理结果
     */
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = new ArrayList<>();
        for(TextSegment segment : textSegments) {
            texts.add(segment.text());
        }
        List<float[]> vectors = ragModelClient.embed(texts);
        List<Embedding> embeddings = new ArrayList<>();
        for(float[] vector : vectors) {
            embeddings.add(Embedding.from(vector));
        }
        return Response.from(embeddings);
    }

    /**
     * 执行 dimension 函数的业务处理。
     * @return 影响行数
     */
    @Override
    public int dimension() {
        return properties.getEmbeddingDimension();
    }

    /**
     * 执行 modelName 函数的业务处理。
     * @return 处理结果
     */
    @Override
    public String modelName() {
        return properties.getModelService().getEmbeddingModel();
    }
}
