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

    public BgeM3EmbeddingModel(RagModelClient ragModelClient, RagProperties properties) {
        this.ragModelClient = ragModelClient;
        this.properties = properties;
    }

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

    @Override
    public int dimension() {
        return properties.getEmbeddingDimension();
    }

    @Override
    public String modelName() {
        return properties.getModelService().getEmbeddingModel();
    }
}
