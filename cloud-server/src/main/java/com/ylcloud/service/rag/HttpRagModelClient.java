package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class HttpRagModelClient implements RagModelClient {
    private final RestClient restClient;

    public HttpRagModelClient(RagProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getModelService().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        EmbedResponse response = restClient.post()
                .uri("/embed")
                .body(new EmbedRequest(texts,true))
                .retrieve()
                .body(EmbedResponse.class);
        if(response == null || response.getVectors() == null) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        for(List<Double> vector : response.getVectors()) {
            float[] values = new float[vector.size()];
            for(int i = 0; i < vector.size(); i++) {
                values[i] = vector.get(i).floatValue();
            }
            result.add(values);
        }
        return result;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, Integer topK) {
        RerankResponse response = restClient.post()
                .uri("/rerank")
                .body(new RerankRequest(query,documents,topK))
                .retrieve()
                .body(RerankResponse.class);
        if(response == null || response.getResults() == null) {
            return List.of();
        }
        return response.getResults();
    }

    @Override
    public RagChatResponse chat(RagChatRequest request) {
        RagChatResponse response = restClient.post()
                .uri("/chat")
                .body(request)
                .retrieve()
                .body(RagChatResponse.class);
        return response == null ? new RagChatResponse() : response;
    }

    private record EmbedRequest(List<String> texts, Boolean normalize) {
    }

    private record RerankRequest(String query, List<String> documents, Integer topK) {
    }

    @lombok.Data
    private static class EmbedResponse {
        private String model;
        private Integer dimension;
        private List<List<Double>> vectors;
    }

    @lombok.Data
    private static class RerankResponse {
        private String model;
        private List<RerankResult> results;
    }
}
