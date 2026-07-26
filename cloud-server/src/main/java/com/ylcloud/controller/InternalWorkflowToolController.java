package com.ylcloud.controller;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeResponse;
import com.ylcloud.workflow.security.ServiceJwtAudience;
import com.ylcloud.workflow.security.ServiceJwtBinding;
import com.ylcloud.workflow.security.ServiceJwtException;
import com.ylcloud.workflow.security.ServiceJwtVerifier;
import com.ylcloud.workflow.tool.WorkflowToolHandler;
import com.ylcloud.workflow.tool.WorkflowToolInvocationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** 仅供 Compose 内 Workflow 调用的 Tool Gateway，不使用浏览器用户 Token。 */
@RestController
@RequestMapping("/internal/v1/tools")
public class InternalWorkflowToolController {
    private final WorkflowToolInvocationService service;
    private final ServiceJwtVerifier verifier;

    public InternalWorkflowToolController(WorkflowToolInvocationService service, ServiceJwtVerifier verifier) {
        this.service = service;
        this.verifier = verifier;
    }

    @PostMapping("/invoke")
    public ToolInvokeResponse invoke(@RequestHeader("Authorization") String authorization,
                                     @RequestBody @Valid ToolInvokeRequest request) {
        WorkflowToolHandler handler = service.requireHandler(request.toolName());
        ServiceJwtBinding binding = new ServiceJwtBinding(request.userId(), request.apiKeyId(), request.sessionId(), null,
                request.runId().toString(), request.executionId().toString(), request.nodeId(),
                request.invocationId().toString());
        try {
            verifier.verify(bearer(authorization), ServiceJwtAudience.TOOL_GATEWAY,
                    Set.of(handler.requiredScope()), binding);
        } catch (ServiceJwtException exception) {
            throw new BaseException("INSUFFICIENT_SERVICE_SCOPE".equals(exception.getCode()) ? 403 : 401,
                    "内部服务身份校验失败");
        }
        return service.invoke(request);
    }

    private String bearer(String value) {
        if (value == null || !value.startsWith("Bearer ")) throw new BaseException(401, "内部服务身份校验失败");
        return value.substring(7).trim();
    }
}
