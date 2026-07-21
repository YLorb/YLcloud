package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.workflow.contract.WorkflowContracts.ContractError;
import com.ylcloud.workflow.contract.WorkflowContracts.ContractErrorCode;
import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeResponse;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeResponse.ToolInvokeStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Invocation ID 是副作用的唯一幂等键；已落库调用只回放结果，绝不重复执行 handler。 */
@Service
public class WorkflowToolInvocationService {
    private final WorkflowToolRegistry registry;
    private final WorkflowToolInvocationMapper mapper;
    private final WorkflowConfirmationService confirmationService;
    private final ObjectMapper objectMapper;
    private final ToolArgumentsCanonicalizer canonicalizer;

    public WorkflowToolInvocationService(WorkflowToolRegistry registry, WorkflowToolInvocationMapper mapper,
                                         WorkflowConfirmationService confirmationService, ObjectMapper objectMapper,
                                         ToolArgumentsCanonicalizer canonicalizer) {
        this.registry = registry;
        this.mapper = mapper;
        this.confirmationService = confirmationService;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public WorkflowToolHandler requireHandler(String name) { return registry.require(name); }

    public ToolInvokeResponse invoke(ToolInvokeRequest request) {
        WorkflowToolHandler handler = registry.require(request.toolName());
        if (handler.riskLevel() != request.riskLevel()) throw new BaseException(409, "Tool 风险等级不匹配");
        String argumentsHash = canonicalizer.hash(request.arguments());
        WorkflowToolInvocationRecord existing = mapper.get(request.invocationId().toString());
        if (existing != null) return replay(existing, request, argumentsHash);
        ToolInvocationContext context = new ToolInvocationContext(request.runId(), request.executionId(),
                request.nodeId(), request.invocationId(), request.userId(), request.sessionId());
        if (handler.riskLevel() == RiskLevel.HIGH) {
            confirmationService.authorize(request.confirmation(), context, handler.name(), argumentsHash, request.arguments());
        }
        WorkflowToolInvocationRecord record = record(request, argumentsHash);
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException exception) {
            WorkflowToolInvocationRecord raced = mapper.get(request.invocationId().toString());
            if (raced == null) throw new BaseException(409, "Tool 调用幂等冲突");
            return replay(raced, request, argumentsHash);
        }
        ToolInvokeResponse response;
        try {
            Map<String, Object> result = handler.invoke(context, request.arguments());
            response = new ToolInvokeResponse("1.0", request.invocationId(), ToolInvokeStatus.SUCCEEDED,
                    result == null ? Map.of() : result, null);
        } catch (BaseException exception) {
            response = failed(request, exception.getStatusCode() == 403 ? ContractErrorCode.FORBIDDEN : ContractErrorCode.INVALID_REQUEST,
                    safeMessage(exception), false);
        } catch (Exception exception) {
            response = failed(request, ContractErrorCode.INTERNAL_ERROR, "Tool 执行失败", false);
        }
        try {
            if (mapper.complete(request.invocationId().toString(), objectMapper.writeValueAsString(response),
                    response.error() == null ? null : response.error().code().name()) != 1)
                throw new BaseException(500, "Tool 结果持久化失败");
        } catch (Exception exception) {
            throw new BaseException(500, "Tool 结果持久化失败");
        }
        return response;
    }

    private ToolInvokeResponse replay(WorkflowToolInvocationRecord existing, ToolInvokeRequest request, String hash) {
        if (!existing.getRunId().equals(request.runId().toString())
                || !existing.getExecutionId().equals(request.executionId().toString())
                || !existing.getNodeId().equals(request.nodeId()) || existing.getUserId() != request.userId()
                || existing.getSessionId() != request.sessionId() || !existing.getToolName().equals(request.toolName())
                || !existing.getArgumentsHash().equals(hash)) throw new BaseException(409, "invocationId 已绑定其他调用");
        if (!"COMPLETED".equals(existing.getInvocationStatus()) || existing.getResponseJson() == null)
            return failed(request, ContractErrorCode.CONFLICT, "Tool 调用结果未知，禁止重复副作用", false);
        try {
            return objectMapper.readValue(existing.getResponseJson(), ToolInvokeResponse.class);
        } catch (Exception exception) {
            throw new BaseException(500, "Tool 幂等结果损坏");
        }
    }

    private WorkflowToolInvocationRecord record(ToolInvokeRequest request, String hash) {
        WorkflowToolInvocationRecord value = new WorkflowToolInvocationRecord();
        value.setInvocationId(request.invocationId().toString()); value.setRunId(request.runId().toString());
        value.setExecutionId(request.executionId().toString()); value.setNodeId(request.nodeId());
        value.setUserId(request.userId()); value.setSessionId(request.sessionId()); value.setToolName(request.toolName());
        value.setRiskLevel(request.riskLevel().name()); value.setArgumentsHash(hash); return value;
    }

    private ToolInvokeResponse failed(ToolInvokeRequest request, ContractErrorCode code, String message, boolean retryable) {
        return new ToolInvokeResponse("1.0", request.invocationId(), ToolInvokeStatus.FAILED, null,
                new ContractError("1.0", code, message, retryable, null));
    }

    private String safeMessage(BaseException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "Tool 请求被拒绝" : value.substring(0, Math.min(512, value.length()));
    }
}
