package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import java.util.Map;

@Component
public class QdrantCollectionInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);
    private static final String[] INTEGER_PAYLOAD_INDEXES = {
            "spaceId",
            "spaceFileId",
            "documentId",
            "status"
    };

    private final RagProperties properties;
    private final RestClient restClient;

    public QdrantCollectionInitializer(RagProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl(properties))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if(!Boolean.TRUE.equals(properties.getVectorEnabled())
                || !Boolean.TRUE.equals(properties.getQdrant().getInitEnabled())) {
            log.info("Qdrant collection initialization skipped");
            return;
        }
        String collectionName = properties.getQdrant().getCollectionName();
        ensureCollection(collectionName);
        for(String fieldName : INTEGER_PAYLOAD_INDEXES) {
            ensurePayloadIndex(collectionName,fieldName);
        }
        log.info("Qdrant collection is ready: {}",collectionName);
    }

    private void ensureCollection(String collectionName) {
        try {
            restClient.get()
                    .uri("/collections/{collectionName}",collectionName)
                    .header("api-key",safeApiKey())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Qdrant collection already exists: {}",collectionName);
        } catch (HttpClientErrorException.NotFound ex) {
            createCollection(collectionName);
        }
    }

    private void createCollection(String collectionName) {
        Map<String, Object> body = Map.of(
                "vectors",Map.of(
                        "size",properties.getEmbeddingDimension(),
                        "distance","Cosine"
                )
        );
        restClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/collections/{collectionName}")
                        .queryParam("wait","true")
                        .build(collectionName))
                .header("api-key",safeApiKey())
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Created Qdrant collection: {}, dimension={}, distance=Cosine",
                collectionName,properties.getEmbeddingDimension());
    }

    private void ensurePayloadIndex(String collectionName, String fieldName) {
        try {
            restClient.put()
                    .uri(uriBuilder -> uriBuilder
                            .path("/collections/{collectionName}/index")
                            .queryParam("wait","true")
                            .build(collectionName))
                    .header("api-key",safeApiKey())
                    .body(Map.of(
                            "field_name",fieldName,
                            "field_schema","integer"
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Ensured Qdrant payload index: {}.{}",collectionName,fieldName);
        } catch (HttpClientErrorException ex) {
            if(isIndexAlreadyExists(ex)) {
                log.info("Qdrant payload index already exists: {}.{}",collectionName,fieldName);
                return;
            }
            throw ex;
        }
    }

    private String baseUrl(RagProperties properties) {
        String scheme = Boolean.TRUE.equals(properties.getQdrant().getUseTls()) ? "https" : "http";
        return scheme + "://" + properties.getQdrant().getHost() + ":" + properties.getQdrant().getRestPort();
    }

    private String safeApiKey() {
        String apiKey = properties.getQdrant().getApiKey();
        return apiKey == null ? "" : apiKey;
    }

    private boolean isIndexAlreadyExists(HttpClientErrorException ex) {
        int statusCode = ex.getStatusCode().value();
        String responseBody = new String(ex.getResponseBodyAsByteArray(), StandardCharsets.UTF_8).toLowerCase();
        return (statusCode == 400 || statusCode == 409) && responseBody.contains("already");
    }
}
