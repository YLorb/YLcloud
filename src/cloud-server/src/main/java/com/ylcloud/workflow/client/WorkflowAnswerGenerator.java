package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.service.KnowledgeRagQueryService;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/** Workflow 只交付结构化 Context；最终自然语言回答始终由 Java 发起生成。 */
@Component
public class WorkflowAnswerGenerator {
    private final ObjectMapper objectMapper;
    private KnowledgeRagQueryService queryService;

    public WorkflowAnswerGenerator(RagModelClient modelClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setQueryService(KnowledgeRagQueryService queryService) { this.queryService = queryService; }

    public String generate(KnowledgeChatMessage message, WorkflowResult result) {
        return generateVerified(message, result).getAnswer();
    }

    /** Frozen tool output is not evidence: re-retrieve within the Java-owned scope and current permissions. */
    public KnowledgeRagQueryVO generateVerified(KnowledgeChatMessage message, WorkflowResult result) {
        try {
            KnowledgeChatQueryCreateDTO input = objectMapper.readValue(
                    message.getRequestJson(), KnowledgeChatQueryCreateDTO.class
            );
            if(input.getSpaceIds() == null || input.getSpaceIds().isEmpty())
                throw new BaseException("WORKFLOW_NO_KNOWLEDGE_SCOPE：未指定知识库，不能生成已验证的知识回答");
            if(queryService == null) throw new BaseException("可信问答服务不可用");
            KnowledgeRagQueryDTO request = new KnowledgeRagQueryDTO();
            request.setQuestion(input.getQuestion());
            request.setSpaceIds(input.getSpaceIds());
            request.setRetrievalMode(input.getRetrievalMode());
            request.setHistory(List.of());
            KnowledgeRagQueryVO response = queryService.query(request, message.getUserId());
            if(response == null || response.getAnswerId() == null || response.getAnswer() == null)
                throw new BaseException("可信回答缺少服务端关联");
            return response;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException("Java 最终回答生成失败");
        }
    }
}
