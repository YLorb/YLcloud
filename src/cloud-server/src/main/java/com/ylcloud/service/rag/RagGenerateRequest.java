package com.ylcloud.service.rag;

import lombok.Data;

@Data
public class RagGenerateRequest {
    private String model;
    private String systemPrompt;
    private String prompt;
    private Integer maxTokens;
    private Double temperature;
}
