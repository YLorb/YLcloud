package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.service.SiteSettingService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class HttpRagModelClient implements RagModelClient {
    private final RagProperties properties;
    private final SiteSettingService siteSettingService;

    /**
     * 初始化 HttpRagModelClient 对象。
     *
     * @param properties 配置属性
     */
    public HttpRagModelClient(RagProperties properties, SiteSettingService siteSettingService) {
        this.properties = properties;
        this.siteSettingService = siteSettingService;
    }

    private RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        String baseUrl = siteSettingService.getString(
                SiteSettingService.RAG_MODEL_SERVICE_BASE_URL,
                properties.getModelService().getBaseUrl()
        );
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 生成向量 embed 相关逻辑。
     *
     * @param texts 文本列表
     * @return 列表结果
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        EmbedResponse response = restClient().post()
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

    /**
     * 重排序 rerank 相关逻辑。
     *
     * @param query 查询内容
     * @param documents 文档列表
     * @param topK 召回数量
     * @return 列表结果
     */
    @Override
    public List<RerankResult> rerank(String query, List<String> documents, Integer topK) {
        RerankResponse response = restClient().post()
                .uri("/rerank")
                .body(new RerankRequest(query,documents,topK))
                .retrieve()
                .body(RerankResponse.class);
        if(response == null || response.getResults() == null) {
            return List.of();
        }
        return response.getResults();
    }

    /**
     * 生成回答 chat 相关逻辑。
     *
     * @param request 请求对象
     * @return 处理结果
     */
    @Override
    public RagChatResponse chat(RagChatRequest request) {
        RagChatResponse response = restClient().post()
                .uri("/chat")
                .body(request)
                .retrieve()
                .body(RagChatResponse.class);
        return response == null ? new RagChatResponse() : response;
    }

    @Override
    public RagGenerateResponse generate(RagGenerateRequest request) {
        RagGenerateResponse response = restClient().post()
                .uri("/generate")
                .body(request)
                .retrieve()
                .body(RagGenerateResponse.class);
        return response == null ? new RagGenerateResponse() : response;
    }

    /**
     * 生成向量 EmbedRequest 相关逻辑。
     *
     * @param texts 文本列表
     * @param normalize 方法入参
     * @return 处理结果
     */
    private record EmbedRequest(List<String> texts, Boolean normalize) {
    }

    /**
     * 重排序 RerankRequest 相关逻辑。
     *
     * @param query 查询内容
     * @param documents 文档列表
     * @param topK 召回数量
     * @return 处理结果
     */
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
