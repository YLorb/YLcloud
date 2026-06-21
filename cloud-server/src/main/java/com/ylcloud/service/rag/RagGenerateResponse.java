package com.ylcloud.service.rag;

import lombok.Data;

@Data
public class RagGenerateResponse {
    private String model;
    private String text;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
}
