package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class ParserServiceClient {
    private final RagProperties ragProperties;
    private final RestClient restClient;

    public ParserServiceClient(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ragProperties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(ragProperties.getReadTimeoutMs());
        String baseUrl = ragProperties.getExtraction() == null ? "http://127.0.0.1:8002" : ragProperties.getExtraction().getParserServiceBaseUrl();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8002" : baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public ParsedDocument parseVlmPage(VlmParseRequest request) {
        if(ragProperties.getExtraction() == null || !Boolean.TRUE.equals(ragProperties.getExtraction().getVlmEnabled())) {
            return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"vlm-page",request.parserVersion(),"VLM parser is disabled");
        }
        try {
            VlmParseResponse response = restClient.post()
                    .uri("/parse/page")
                    .body(request)
                    .retrieve()
                    .body(VlmParseResponse.class);
            if(response == null || !response.isSuccess()) {
                String message = response == null ? "VLM parser returned empty response" : response.getErrorMessage();
                return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"vlm-page",request.parserVersion(),message);
            }
            return ParsedDocument.success(request.fileUuid(),request.fileHash(),response.getParser(),response.getParserVersion(),
                    response.getFullText(),response.getBlocks());
        } catch (RestClientException ex) {
            return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"vlm-page",request.parserVersion(),
                    "VLM parser request failed: " + ex.getMessage());
        }
    }

    public ParsedDocument parseOcr(OcrParseRequest request) {
        if(ragProperties.getExtraction() == null || !Boolean.TRUE.equals(ragProperties.getExtraction().getOcrEnabled())) {
            return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"ocr-layout",request.parserVersion(),"OCR parser is disabled");
        }
        try {
            OcrParseResponse response = restClient.post()
                    .uri("/parse/ocr")
                    .body(request)
                    .retrieve()
                    .body(OcrParseResponse.class);
            if(response == null || !response.isSuccess()) {
                String message = response == null ? "OCR parser returned empty response" : response.getErrorMessage();
                return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"ocr-layout",request.parserVersion(),message);
            }
            return ParsedDocument.success(request.fileUuid(),request.fileHash(),response.getParser(),response.getParserVersion(),
                    response.getFullText(),response.getBlocks());
        } catch (RestClientException ex) {
            return ParsedDocument.failed(request.fileUuid(),request.fileHash(),"ocr-layout",request.parserVersion(),
                    "OCR parser request failed: " + ex.getMessage());
        }
    }

    public record VlmParseRequest(String fileUuid, String fileHash, String fileName, String fileType,
                                  Integer pageNo, String parserVersion) {
    }

    public record OcrParseRequest(String fileUuid, String fileHash, String fileName, String fileType,
                                  String objectUrl, Integer maxPages, String parserVersion) {
    }

    @lombok.Data
    public static class VlmParseResponse {
        private boolean success;
        private String parser;
        private String parserVersion;
        private String fullText;
        private List<DocumentBlock> blocks;
        private String errorMessage;
    }

    @lombok.Data
    public static class OcrParseResponse {
        private boolean success;
        private String parser;
        private String parserVersion;
        private String fullText;
        private List<DocumentBlock> blocks;
        private String errorMessage;
        private List<String> warnings;
    }
}
