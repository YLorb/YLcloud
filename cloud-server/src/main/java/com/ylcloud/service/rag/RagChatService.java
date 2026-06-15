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
    public RagChatResult answer(String question, List<FileRagChunk> chunks, SpaceRagConfig config) {
        if(chunks == null || chunks.isEmpty()) {
            return RagChatResult.success(properties.getChat().getNoAnswerText());
        }
        if(!Boolean.TRUE.equals(properties.getChat().getEnabled())) {
            return RagChatResult.failed(fallbackAnswer(),"RAG chat is disabled");
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
                return RagChatResult.failed(properties.getChat().getNoAnswerText(),"RAG chat returned empty answer");
            }
            return RagChatResult.success(response.getAnswer());
        } catch (Exception ex) {
            log.warn("RAG chat generation failed",ex);
            return RagChatResult.failed(fallbackAnswer(),truncate(ex.getMessage(),1000));
        }
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
    private String resolveChatModel(SpaceRagConfig config) {
        if(config != null && config.getChatModel() != null && !config.getChatModel().isBlank()) {
            return config.getChatModel();
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
