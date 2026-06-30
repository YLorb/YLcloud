package com.ylcloud.service.knowledge.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeProfileParser {
    private static final int MAX_QUESTIONS = 8;
    private final ObjectMapper objectMapper;

    public KnowledgeProfileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KnowledgeProfileParseResult parse(String rawOutput) {
        if(rawOutput == null || rawOutput.isBlank()) {
            return KnowledgeProfileParseResult.failed("LLM_OUTPUT_EMPTY","LLM output is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(rawOutput));
            KnowledgeProfileDraft profile = new KnowledgeProfileDraft();
            profile.setTitle(textValue(root,"title"));
            profile.setSummary(textValue(root,"summary"));
            profile.setKeywords(stringArray(root,"keywords",12));
            profile.setTags(stringArray(root,"tags",8));
            profile.setCategory(textValue(root,"category"));
            profile.setLanguage(textValue(root,"language"));
            profile.setDocumentType(textValue(root,"documentType"));
            profile.setQuestions(stringArray(root,"questions",MAX_QUESTIONS));
            profile.setSchemaValid(true);
            return KnowledgeProfileParseResult.success(profile);
        } catch (Exception ex) {
            return KnowledgeProfileParseResult.failed("LLM_OUTPUT_INVALID_JSON",ex.getMessage());
        }
    }

    private String extractJsonObject(String text) {
        String value = text.trim();
        if(value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*", "");
            int fence = value.lastIndexOf("```");
            if(fence >= 0) {
                value = value.substring(0,fence);
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if(start >= 0 && end > start) {
            return value.substring(start,end + 1);
        }
        return value;
    }

    private String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private List<String> stringArray(JsonNode root, String field, int limit) {
        JsonNode node = root.get(field);
        List<String> values = new ArrayList<>();
        if(node == null) {
            return values;
        }
        if(node.isArray()) {
            for(JsonNode item : node) {
                if(item != null && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                }
                if(values.size() >= limit) {
                    break;
                }
            }
        } else if(node.isTextual() && !node.asText().isBlank()) {
            values.add(node.asText().trim());
        }
        return values;
    }
}
