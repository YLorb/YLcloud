package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.DTO.RagChatMessageDTO;
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
    private static final String LEGACY_ARK_MODEL = "doubao-seed2.0";

    private final RagModelClient ragModelClient;
    private final RagProperties properties;

    /**
     * 初始化 RagChatService 对象。
     *
     * @param ragModelClient RAG 模型客户端
     * @param properties 配置属性
     */
    public RagChatService(RagModelClient ragModelClient, RagProperties properties) {
        this.ragModelClient = ragModelClient;
        this.properties = properties;
    }

    /**
     * 执行 answer 函数的业务处理。
     *
     * @param question 问题内容
     * @param chunks 文件分片列表
     * @param config 配置对象
     * @return 处理结果
     */
    public RagChatResult answer(String question, List<FileRagChunk> chunks, SpaceRagConfig config, List<RagChatMessageDTO> history) {
        if(chunks == null || chunks.isEmpty()) {
            return RagChatResult.noAnswer(properties.getChat().getNoAnswerText());
        }
        if(!Boolean.TRUE.equals(properties.getChat().getEnabled())) {
            return RagChatResult.failed(fallbackAnswer(),"RAG chat is disabled");
        }
        try {
            RagChatRequest request = new RagChatRequest();
            String model = resolveChatModel(config);
            request.setModel(model);
            request.setSystemPrompt(properties.getChat().getSystemPrompt());
            request.setQuestion(question);
            request.setContexts(buildContexts(chunks));
            request.setHistory(history == null ? List.of() : history);
            request.setMaxTokens(properties.getChat().getMaxAnswerTokens());
            request.setTemperature(resolveTemperature(config));
            RagChatResponse response = ragModelClient.chat(request);
            if(response.getAnswer() == null || response.getAnswer().isBlank()) {
                RagChatResult result = RagChatResult.noAnswer(properties.getChat().getNoAnswerText(),"RAG chat returned empty answer");
                result.setModelName(model);
                return result;
            }
            RagChatResult result = isNoAnswerResponse(response.getAnswer())
                    ? RagChatResult.noAnswer(response.getAnswer())
                    : RagChatResult.success(response.getAnswer());
            result.setModelName(model);
            return result;
        } catch (Exception ex) {
            log.warn("RAG chat generation failed",ex);
            RagChatResult result = RagChatResult.failed(fallbackAnswer(),truncate(ex.getMessage(),1000));
            result.setModelName(resolveChatModel(config));
            return result;
        }
    }

    public RagChatResult answer(String question, List<FileRagChunk> chunks, SpaceRagConfig config) {
        return answer(question,chunks,config,List.of());
    }

    /**
     * 构建 buildContexts 相关逻辑。
     *
     * @param chunks 文件分片列表
     * @return 列表结果
     */
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

    /**
     * 解析 resolveChatModel 相关逻辑。
     *
     * @param config 配置对象
     * @return 处理结果
     */
    private double resolveTemperature(SpaceRagConfig config) {
        Double configured = config == null || config.getTemperature() == null ? null : config.getTemperature().doubleValue();
        double value = configured == null ? (properties.getChat().getTemperature() == null ? 0.2 : properties.getChat().getTemperature()) : configured;
        return Math.max(0.0,Math.min(1.0,value));
    }

    private String resolveChatModel(SpaceRagConfig config) {
        String configuredModel = config == null ? null : config.getChatModel();
        if(configuredModel != null && !configuredModel.isBlank() && !LEGACY_ARK_MODEL.equals(configuredModel)) {
            return configuredModel;
        }
        return properties.getModelService().getChatModel();
    }

    /**
     * 执行 fallbackAnswer 函数的业务处理。
     * @return 处理结果
     */
    private String fallbackAnswer() {
        return properties.getChat().getUnavailableText();
    }

    private boolean isNoAnswerResponse(String answer) {
        if(answer == null || answer.isBlank()) {
            return true;
        }
        String normalized = answer.replaceAll("\\s+","").trim();
        String configured = properties.getChat().getNoAnswerText();
        if(configured != null && normalized.equals(configured.replaceAll("\\s+","").trim())) {
            return true;
        }
        return normalized.contains("无法从当前知识库回答")
                || normalized.contains("当前知识库中没有检索到足够的依据")
                || (normalized.contains("没有检索到足够") && normalized.contains("无法回答"));
    }

    /**
     * 执行 positive 函数的业务处理。
     *
     * @param value 方法入参
     * @param defaultValue 方法入参
     * @return 影响行数
     */
    private int positive(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    /**
     * 执行 truncate 函数的业务处理。
     *
     * @param value 方法入参
     * @param maxLength 方法入参
     * @return 处理结果
     */
    private String truncate(String value, int maxLength) {
        if(value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0,maxLength);
    }
}
