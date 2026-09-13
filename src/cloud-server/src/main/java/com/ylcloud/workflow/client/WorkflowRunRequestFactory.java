package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.workflow.contract.WorkflowContracts.KnowledgeScopeDecision;
import com.ylcloud.workflow.contract.WorkflowContracts.RunBudget;
import com.ylcloud.workflow.contract.WorkflowContracts.ShortTermMessage;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunCreateRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowType;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowVersion;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造 Run 初始 Snapshot：只含原问题、短期会话、权限/知识范围和配置。
 * 不在 Java 预先召回长期记忆或文档，召回选择由新 Workflow Graph 决定。
 */
@Component
public class WorkflowRunRequestFactory {
    static final String CONFIG_VERSION = "assistant-workflow-v1";
    private final KnowledgeChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public WorkflowRunRequestFactory(
            KnowledgeChatMessageMapper messageMapper,
            ObjectMapper objectMapper
    ) {
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public PreparedWorkflowRun create(KnowledgeChatMessage assistant) {
        try {
            KnowledgeChatQueryCreateDTO input = objectMapper.readValue(
                    assistant.getRequestJson(), KnowledgeChatQueryCreateDTO.class
            );
            KnowledgeChatMessage source = messageMapper.getOwned(
                    assistant.getSourceMessageId(), assistant.getSessionId(), assistant.getUserId()
            );
            if (source == null || !"user".equals(source.getRole())) {
                throw new BaseException("Workflow 来源消息不存在");
            }
            List<KnowledgeChatMessage> candidates = messageMapper.listContextCandidates(
                    assistant.getSessionId(), assistant.getUserId(), source.getSequenceNo()
            );
            int from = Math.max(0, candidates.size() - 50);
            List<ShortTermMessage> shortContext = new ArrayList<>();
            for (KnowledgeChatMessage message : candidates.subList(from, candidates.size())) {
                ShortTermMessage.Role role = "assistant".equals(message.getRole())
                        ? ShortTermMessage.Role.ASSISTANT : ShortTermMessage.Role.USER;
                shortContext.add(new ShortTermMessage(role, truncate(message.getContent(), 32_000)));
            }
            List<Long> spaces = input.getSpaceIds() == null
                    ? List.of() : input.getSpaceIds().stream().filter(id -> id != null && id > 0).distinct().toList();
            Map<String, Object> permissionScope = new LinkedHashMap<>();
            permissionScope.put("userId", assistant.getUserId());
            permissionScope.put("sessionId", assistant.getSessionId());
            permissionScope.put("allowedSpaceIds", spaces);
            if (input.getApiKeyId() != null) {
                permissionScope.put("apiKeyId", input.getApiKeyId());
            }
            KnowledgeScopeDecision knowledgeScope = new KnowledgeScopeDecision(
                    true, spaces, List.of()
            );
            RunBudget budget = new RunBudget(6, 3, 8, 120_000);
            String question = truncate(source.getContent(), 32_000);
            String contextHash = requestContextHash(
                    question, shortContext, permissionScope, knowledgeScope, budget
            );
            WorkflowRunCreateRequest request = new WorkflowRunCreateRequest(
                    "1.0", WorkflowType.ASSISTANT, WorkflowVersion.V2,
                    assistant.getUserId(), assistant.getSessionId(), source.getId(), assistant.getId(),
                    question, shortContext, permissionScope, knowledgeScope,
                    CONFIG_VERSION, contextHash, budget
            );
            String idempotencyKey = "assistant:" + assistant.getId() + ":" + contextHash + ":2.0";
            return new PreparedWorkflowRun(request, idempotencyKey);
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException("Workflow 请求快照构建失败");
        }
    }

    private String requestContextHash(
            String question,
            List<ShortTermMessage> shortContext,
            Map<String, Object> permissionScope,
            KnowledgeScopeDecision knowledgeScope,
            RunBudget budget
    ) throws Exception {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("question", question);
        canonical.put("shortTermContext", shortContext);
        canonical.put("permissionScope", permissionScope);
        canonical.put("knowledgeScope", knowledgeScope);
        canonical.put("configVersion", CONFIG_VERSION);
        canonical.put("budget", budget);
        byte[] json = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
        return java.util.HexFormat.of().formatHex(digest);
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record PreparedWorkflowRun(
            WorkflowRunCreateRequest request,
            String idempotencyKey
    ) {
    }
}
