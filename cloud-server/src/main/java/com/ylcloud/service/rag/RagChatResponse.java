package com.ylcloud.service.rag;

import lombok.Data;

@Data
public class RagChatResponse {
    private String model;
    private String answer;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
}
