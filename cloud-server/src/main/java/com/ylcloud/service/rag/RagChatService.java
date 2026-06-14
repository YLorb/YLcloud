package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceRagConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagChatService {
    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final RagModelClient ragModelClient;
    private final RagProperties properties;

    public RagChatService(RagModelClient ragModelClient, RagProperties properties) {
        this.ragModelClient = ragModelClient;
        this.properties = properties;
    }

    public String answer(String question, List<FileRagChunk> chunks, SpaceRagConfig config) {
        if(chunks == null || chunks.isEmpty()) {
            return properties.getChat().getNoAnswerText();
        }
        if(!Boolean.TRUE.equals(properties.getChat().getEnabled())) {
            return fallbackAnswer();
        }
        try {
            RagChatRequest request = new RagChatRequest();
            request.setModel(resolveChatModel(config));
            request.setSystemPrompt(properties.getChat().getSystemPrompt());
            request.setQuestion(question);
            request.setContexts(buildContexts(chunks));
            request.setMaxTokens(properties.getChat().getMaxAnswerTokens());
            request.setTemperature(properties.getChat().getTemperature());
            RagChatResponse response = ragModelClient.chat(request);
            if(response.getAnswer() == null || response.getAnswer().isBlank()) {
                return properties.getChat().getNoAnswerText();
            }
            return response.getAnswer();
        } catch (Exception ex) {
            log.warn("RAG chat generation failed",ex);
            return fallbackAnswer();
        }
    }

    private List<String> buildContexts(List<FileRagChunk> chunks) {
        int maxContextChars = positive(properties.getChat().getMaxContextChars(),12000);
        int maxChunkChars = positive(properties.getChat().getMaxChunkChars(),1800);
        List<String> contexts = new ArrayList<>();
        int used = 0;
        for(int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i).getContent();
            if(content == null || content.isBlank()) {
                continue;
            }
            String numbered = "[" + (i + 1) + "] " + truncate(content,maxChunkChars);
            if(used + numbered.length() > maxContextChars) {
                break;
            }
            contexts.add(numbered);
            used += numbered.length();
        }
        return contexts;
    }

    private String resolveChatModel(SpaceRagConfig config) {
        if(config != null && config.getChatModel() != null && !config.getChatModel().isBlank()) {
            return config.getChatModel();
        }
        return properties.getModelService().getChatModel();
    }

    private String fallbackAnswer() {
        return properties.getChat().getUnavailableText();
    }

    private int positive(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private String truncate(String value, int maxLength) {
        if(value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0,maxLength);
    }
}
