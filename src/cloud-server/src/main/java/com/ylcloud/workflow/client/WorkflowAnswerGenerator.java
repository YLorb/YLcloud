package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.service.rag.RagChatRequest;
import com.ylcloud.service.rag.RagChatResponse;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import org.springframework.stereotype.Component;

import java.util.List;

/** Workflow 只交付结构化 Context；最终自然语言回答始终由 Java 发起生成。 */
@Component
public class WorkflowAnswerGenerator {
    private static final String SYSTEM_PROMPT = "你是 YLcloud 助手。根据 Java 提供的问题与已冻结的 Workflow Context 回答。"
            + "不得声称执行未出现在结构化结果中的操作；Context 不足时应明确说明。";
    private final RagModelClient modelClient;
    private final ObjectMapper objectMapper;

    public WorkflowAnswerGenerator(RagModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public String generate(KnowledgeChatMessage message, WorkflowResult result) {
        try {
            KnowledgeChatQueryCreateDTO input = objectMapper.readValue(
                    message.getRequestJson(), KnowledgeChatQueryCreateDTO.class
            );
            RagChatRequest request = new RagChatRequest();
            request.setSystemPrompt(SYSTEM_PROMPT);
            request.setQuestion(input.getQuestion());
            request.setContexts(List.of(result.snapshotDraft().injectableContext()));
            request.setHistory(List.of());
            request.setMaxTokens(1_024);
            request.setTemperature(0.2);
            RagChatResponse response = modelClient.chat(request);
            if (response == null || response.getAnswer() == null || response.getAnswer().isBlank()) {
                throw new BaseException("Java 最终回答生成结果为空");
            }
            return response.getAnswer().trim();
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException("Java 最终回答生成失败");
        }
    }
}
