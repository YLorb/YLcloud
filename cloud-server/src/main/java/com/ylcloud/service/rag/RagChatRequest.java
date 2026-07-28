package com.ylcloud.service.rag;

import com.ylcloud.DTO.RagChatMessageDTO;
import lombok.Data;

import java.util.List;

@Data
public class RagChatRequest {
    private String model;
    private String systemPrompt;
    private String question;
    private List<String> contexts;
    private List<RagChatMessageDTO> history;
    private Integer maxTokens;
    private Double temperature;
}
