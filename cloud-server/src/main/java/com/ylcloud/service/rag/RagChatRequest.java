package com.ylcloud.service.rag;

import lombok.Data;

import java.util.List;

@Data
public class RagChatRequest {
    private String model;
    private String systemPrompt;
    private String question;
    private List<String> contexts;
    private Integer maxTokens;
    private Double temperature;
}
