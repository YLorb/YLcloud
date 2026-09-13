package com.ylcloud.service.rag.query;

import com.ylcloud.DTO.RagChatMessageDTO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class QueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final int MAX_HISTORY_ITEMS = 6;
    private static final int MAX_GENERATED_TEXT_LENGTH = 2000;

    private static final String REWRITE_PROMPT_TEMPLATE = """
            请将用户的问题改写成更适合知识库检索的表述。
            要求：去掉口语化表达、补全缩写和指代、使用更正式的书面语、保留核心意图。

            对话历史：%s
            用户问题：%s

            只输出改写后的问题，不要解释：
            """;

    private static final String HYDE_PROMPT_TEMPLATE = """
            请根据以下问题，生成一段可能的答案。
            注意：不需要完全准确，只需要用于辅助检索，风格尽量像知识库文档。

            问题：%s
            假设答案：
            """;

    private static final String STEP_BACK_PROMPT_TEMPLATE = """
            请将以下具体问题转化为一个更通用的背景问题，用于检索相关背景知识。

            具体问题：%s
            背景问题：
            """;

    private static final String MULTI_QUERY_PROMPT_TEMPLATE = """
            请将用户问题改写成 3 到 5 个适合知识库检索的不同角度问题。
            要求：覆盖不同关键词、不同表述方式和可能的上位概念；保留核心意图。

            对话历史：%s
            用户问题：%s

            每行输出一个问题，不要编号，不要解释：
            """;

    private final RagProperties ragProperties;
    private final RagModelClient ragModelClient;
    private final Executor queryExecutor;

    public QueryRewriteService(RagProperties ragProperties, RagModelClient ragModelClient) {
        this(ragProperties,ragModelClient,Runnable::run);
    }

    @Autowired
    public QueryRewriteService(RagProperties ragProperties, RagModelClient ragModelClient,
                               @Qualifier("ragQueryExecutor") Executor queryExecutor) {
        this.ragProperties = ragProperties;
        this.ragModelClient = ragModelClient;
        this.queryExecutor = queryExecutor;
    }

    public QueryPlan plan(String question) {
        return plan(question,List.of());
    }

    public QueryPlan plan(String question, List<RagChatMessageDTO> history) {
        QueryPlan plan = localPlan(question);
        RagProperties.Query queryProperties = ragProperties.getQuery();
        String historyText = formatHistory(history);
        int historyCount = history == null ? 0 : Math.min(history.size(),MAX_HISTORY_ITEMS);
        log.info("RAG query rewrite planning started: questionChars={}, historyCount={}",
                question == null ? 0 : question.length(),historyCount);
        if(queryProperties == null || !Boolean.TRUE.equals(queryProperties.getRewriteEnabled())) {
            plan.setRewriteSource("disabled");
            log.info("RAG query rewrite skipped: rewrite disabled");
            return plan;
        }
        if(Boolean.TRUE.equals(queryProperties.getModelRewriteEnabled())) {
            applyRewrite(plan,queryProperties,historyText);
        } else {
            plan.setRewriteSource("local");
        }
        List<CompletableFuture<Void>> expansions = new ArrayList<>();
        if(Boolean.TRUE.equals(queryProperties.getMultiQueryEnabled())) {
            expansions.add(CompletableFuture.runAsync(() -> applyMultiQuery(plan,queryProperties,historyText),queryExecutor));
        }
        if(Boolean.TRUE.equals(queryProperties.getHydeEnabled())) {
            expansions.add(CompletableFuture.runAsync(() -> applyHyde(plan,queryProperties),queryExecutor));
        }
        if(Boolean.TRUE.equals(queryProperties.getStepBackEnabled())) {
            expansions.add(CompletableFuture.runAsync(() -> applyStepBack(plan,queryProperties),queryExecutor));
        }
        CompletableFuture.allOf(expansions.toArray(new CompletableFuture[0])).join();
        log.info("RAG query rewrite planning finished: rewritten={}, multiQueries={}, hyde={}, stepBack={}, warnings={}",
                present(plan.getRewrittenQuery()),plan.getExpandedQueries().size(),present(plan.getHydeDocument()),
                present(plan.getStepBackQuery()),plan.getWarnings().size());
        return plan;
    }

    private QueryPlan localPlan(String question) {
        String normalized = normalize(question);
        QueryPlan plan = new QueryPlan();
        plan.setOriginal(question);
        plan.setNormalized(normalized);
        plan.setKeywords(keywords(normalized));
        plan.setIntent(detectIntent(normalized));
        plan.setRewriteSource("local");
        if(!normalized.isBlank()) {
            plan.setStepBackQuery(stepBack(normalized));
            plan.setHydeDocument("This document may answer: " + normalized);
        }
        return plan;
    }

    private void applyRewrite(QueryPlan plan, RagProperties.Query queryProperties, String historyText) {
        try {
            String prompt = REWRITE_PROMPT_TEMPLATE.formatted(historyText,plan.getOriginal());
            String rewritten = cleanSingleOutput(generate(queryProperties,prompt,256,0.1));
            if(!rewritten.isBlank()) {
                plan.setRewrittenQuery(rewritten);
                plan.setRewriteSource("llm");
                plan.setKeywords(keywords(rewritten));
                log.info("RAG query rewrite generated: chars={}",rewritten.length());
            }
        } catch (Exception ex) {
            addWarning(plan,"rewrite_failed");
            log.warn("RAG query rewrite LLM call failed, using local query",ex);
        }
    }

    private void applyMultiQuery(QueryPlan plan, RagProperties.Query queryProperties, String historyText) {
        try {
            String prompt = MULTI_QUERY_PROMPT_TEMPLATE.formatted(historyText,queryForPrompt(plan));
            List<String> queries = cleanLines(generate(queryProperties,prompt,512,0.2));
            int max = queryProperties.getMaxExpandedQueries() == null ? 4 : Math.max(1,queryProperties.getMaxExpandedQueries());
            queries = limitDistinct(queries,max);
            if(queries.size() < 3) {
                queries = fillLocalMultiQueries(plan,queries,max);
            }
            plan.setExpandedQueries(queries);
            log.info("RAG multi-query expansion generated: count={}",queries.size());
        } catch (Exception ex) {
            addWarning(plan,"multi_query_failed");
            plan.setExpandedQueries(fillLocalMultiQueries(plan,List.of(),defaultExpandedLimit(queryProperties)));
            log.warn("RAG multi-query LLM call failed, using local expanded queries",ex);
        }
    }

    private void applyHyde(QueryPlan plan, RagProperties.Query queryProperties) {
        try {
            String prompt = HYDE_PROMPT_TEMPLATE.formatted(queryForPrompt(plan));
            String hydeDocument = cleanTextOutput(generate(queryProperties,prompt,768,0.2));
            if(!hydeDocument.isBlank()) {
                plan.setHydeDocument(hydeDocument);
                log.info("RAG HyDE document generated: chars={}",hydeDocument.length());
            }
        } catch (Exception ex) {
            addWarning(plan,"hyde_failed");
            log.warn("RAG HyDE LLM call failed, using local HyDE fallback",ex);
        }
    }

    private void applyStepBack(QueryPlan plan, RagProperties.Query queryProperties) {
        try {
            String prompt = STEP_BACK_PROMPT_TEMPLATE.formatted(plan.getOriginal());
            String stepBackQuery = cleanSingleOutput(generate(queryProperties,prompt,256,0.1));
            if(!stepBackQuery.isBlank()) {
                plan.setStepBackQuery(stepBackQuery);
                log.info("RAG step-back query generated: chars={}",stepBackQuery.length());
            }
        } catch (Exception ex) {
            addWarning(plan,"step_back_failed");
            log.warn("RAG step-back LLM call failed, using local step-back fallback",ex);
        }
    }

    private String generate(RagProperties.Query queryProperties, String prompt, int maxTokens, double temperature) {
        RagGenerateRequest request = new RagGenerateRequest();
        request.setModel(queryProperties.getModel());
        request.setSystemPrompt("你是知识库检索查询改写助手。严格按用户要求输出，不要添加解释。");
        request.setPrompt(prompt);
        request.setMaxTokens(maxTokens);
        request.setTemperature(temperature);
        RagGenerateResponse response = ragModelClient.generate(request);
        return response == null || response.getText() == null ? "" : response.getText();
    }

    private String formatHistory(List<RagChatMessageDTO> history) {
        if(history == null || history.isEmpty()) {
            return "无";
        }
        int start = Math.max(0,history.size() - MAX_HISTORY_ITEMS);
        List<String> lines = new ArrayList<>();
        for(int i = start; i < history.size(); i++) {
            RagChatMessageDTO message = history.get(i);
            if(message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String role = message.getRole() == null || message.getRole().isBlank() ? "user" : message.getRole();
            lines.add(role + ": " + truncate(message.getContent().trim(),500));
        }
        return lines.isEmpty() ? "无" : String.join("\n",lines);
    }

    private String cleanSingleOutput(String value) {
        List<String> lines = cleanLines(value);
        if(lines.isEmpty()) {
            return "";
        }
        return truncate(lines.get(0),MAX_GENERATED_TEXT_LENGTH);
    }

    private String cleanTextOutput(String value) {
        List<String> lines = cleanLines(value);
        if(lines.isEmpty()) {
            return "";
        }
        return truncate(String.join("\n",lines),MAX_GENERATED_TEXT_LENGTH);
    }

    private List<String> cleanLines(String text) {
        List<String> result = new ArrayList<>();
        if(text == null || text.isBlank()) {
            return result;
        }
        String normalized = text.replace("```json","").replace("```","").trim();
        for(String line : normalized.split("\\R")) {
            String value = line.replaceFirst("^\\s*[-*•]+\\s*","")
                    .replaceFirst("^\\s*\\d+[.)、]\\s*","")
                    .replaceFirst("(?i)^(query|rewrite|hyde|stepback|step-back|background|背景问题|假设答案)\\s*[:：]\\s*","")
                    .trim();
            if(!value.isBlank()) {
                result.add(truncate(value,MAX_GENERATED_TEXT_LENGTH));
            }
        }
        if(result.isEmpty() && !normalized.isBlank()) {
            result.add(truncate(normalized,MAX_GENERATED_TEXT_LENGTH));
        }
        return result;
    }

    private List<String> fillLocalMultiQueries(QueryPlan plan, List<String> current, int max) {
        List<String> result = new ArrayList<>(current);
        addDistinct(result,queryForPrompt(plan),max);
        if(plan.getStepBackQuery() != null) {
            addDistinct(result,plan.getStepBackQuery(),max);
        }
        for(String keyword : plan.getKeywords()) {
            addDistinct(result,keyword,max);
        }
        return result;
    }

    private List<String> limitDistinct(List<String> values, int max) {
        List<String> result = new ArrayList<>();
        for(String value : values) {
            addDistinct(result,value,max);
        }
        return result;
    }

    private void addDistinct(List<String> values, String value, int max) {
        if(value == null || value.isBlank() || values.size() >= max) {
            return;
        }
        String trimmed = value.trim();
        if(!values.contains(trimmed)) {
            values.add(trimmed);
        }
    }

    private int defaultExpandedLimit(RagProperties.Query queryProperties) {
        return queryProperties == null || queryProperties.getMaxExpandedQueries() == null
                ? 4 : Math.max(1,queryProperties.getMaxExpandedQueries());
    }

    private String queryForPrompt(QueryPlan plan) {
        if(plan.getRewrittenQuery() != null && !plan.getRewrittenQuery().isBlank()) {
            return plan.getRewrittenQuery();
        }
        return plan.getOriginal() == null ? "" : plan.getOriginal();
    }

    private synchronized void addWarning(QueryPlan plan, String warning) {
        if(plan.getWarnings() == null) {
            plan.setWarnings(new ArrayList<>());
        }
        plan.getWarnings().add(warning);
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

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if(value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0,maxLength);
    }
}
