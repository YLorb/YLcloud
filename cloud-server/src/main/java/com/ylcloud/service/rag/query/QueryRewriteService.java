package com.ylcloud.service.rag.query;

import com.ylcloud.config.RagProperties;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class QueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final RagProperties ragProperties;
    private final RagModelClient ragModelClient;

    public QueryRewriteService(RagProperties ragProperties, RagModelClient ragModelClient) {
        this.ragProperties = ragProperties;
        this.ragModelClient = ragModelClient;
    }

    public QueryPlan plan(String question) {
        QueryPlan plan = localPlan(question);
        RagProperties.Query queryProperties = ragProperties.getQuery();
        if(queryProperties == null || !Boolean.TRUE.equals(queryProperties.getRewriteEnabled())
                || !Boolean.TRUE.equals(queryProperties.getModelRewriteEnabled())) {
            return plan;
        }
        try {
            enrichWithModel(plan,queryProperties);
        } catch (Exception ex) {
            log.warn("RAG query rewrite failed, using local query plan",ex);
        }
        return plan;
    }

    private QueryPlan localPlan(String question) {
        String normalized = normalize(question);
        QueryPlan plan = new QueryPlan();
        plan.setOriginal(question);
        plan.setNormalized(normalized);
        plan.setKeywords(keywords(normalized));
        plan.setIntent(detectIntent(normalized));
        if(!normalized.isBlank()) {
            plan.setStepBackQuery(stepBack(normalized));
            plan.setHydeDocument("This document may answer: " + normalized);
        }
        return plan;
    }

    private void enrichWithModel(QueryPlan plan, RagProperties.Query queryProperties) {
        RagGenerateRequest request = new RagGenerateRequest();
        request.setModel(queryProperties.getModel());
        request.setSystemPrompt("Rewrite the user query for retrieval. Return plain text lines only.");
        request.setPrompt(buildPrompt(plan,queryProperties));
        request.setMaxTokens(512);
        request.setTemperature(0.1);
        RagGenerateResponse response = ragModelClient.generate(request);
        if(response == null || response.getText() == null || response.getText().isBlank()) {
            return;
        }
        List<String> lines = parseLines(response.getText());
        if(lines.isEmpty()) {
            return;
        }
        int max = queryProperties.getMaxExpandedQueries() == null ? 4 : Math.max(1,queryProperties.getMaxExpandedQueries());
        List<String> expanded = new ArrayList<>();
        for(String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            String value = stripLabel(line);
            if(value.isBlank()) {
                continue;
            }
            if(lower.startsWith("hyde") && Boolean.TRUE.equals(queryProperties.getHydeEnabled())) {
                plan.setHydeDocument(value);
            } else if((lower.startsWith("step") || lower.startsWith("back"))
                    && Boolean.TRUE.equals(queryProperties.getStepBackEnabled())) {
                plan.setStepBackQuery(value);
            } else if(Boolean.TRUE.equals(queryProperties.getMultiQueryEnabled()) && expanded.size() < max) {
                expanded.add(value);
            }
        }
        if(!expanded.isEmpty()) {
            plan.setExpandedQueries(expanded);
        }
    }

    private String buildPrompt(QueryPlan plan, RagProperties.Query queryProperties) {
        return """
                User question:
                %s

                Produce up to %d retrieval rewrites. Include optional lines prefixed with:
                query: ...
                hyde: ...
                stepback: ...
                """.formatted(plan.getOriginal(),queryProperties.getMaxExpandedQueries() == null ? 4 : queryProperties.getMaxExpandedQueries());
    }

    private List<String> parseLines(String text) {
        List<String> result = new ArrayList<>();
        for(String line : text.split("\\R")) {
            String value = line.replaceFirst("^[-*\\d.\\s]+","").trim();
            if(!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private String stripLabel(String line) {
        return line.replaceFirst("(?i)^(query|hyde|stepback|step-back|back)\\s*[:：]\\s*","").trim();
    }

    private String normalize(String question) {
        return question == null ? "" : question.replaceAll("\\s+"," ").trim();
    }

    private List<String> keywords(String question) {
        Set<String> result = new LinkedHashSet<>();
        if(question != null && !question.isBlank()) {
            result.add(question.trim());
            for(String part : question.split("[\\s,;:\\uFF0C\\u3002\\uFF1B\\uFF1A\\u3001]+")) {
                String keyword = part.trim();
                if(keyword.length() >= 2) {
                    result.add(keyword);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private String detectIntent(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if(value.contains("代码") || value.contains("code") || value.contains("class") || value.contains("function")) {
            return "code";
        }
        if(value.contains("表格") || value.contains("table") || value.contains("行") || value.contains("列")) {
            return "table_lookup";
        }
        return "general";
    }

    private String stepBack(String question) {
        if(question == null || question.isBlank()) {
            return question;
        }
        return "Background and key facts about: " + question;
    }
}
