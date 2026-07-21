package com.ylcloud.controller;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.ToolInvokeResponse;
import com.ylcloud.workflow.security.*;
import com.ylcloud.workflow.tool.WorkflowToolHandler;
import com.ylcloud.workflow.tool.WorkflowToolInvocationService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InternalWorkflowToolControllerTest {
    private static final String SECRET = "active-service-secret-32-bytes-minimum-0001";

    @Test
    void requiresAudienceScopeAndEveryResourceBinding() {
        WorkflowToolInvocationService service = mock(WorkflowToolInvocationService.class);
        WorkflowToolHandler handler = mock(WorkflowToolHandler.class);
        when(handler.requiredScope()).thenReturn("tool.web.search");
        when(service.requireHandler("web.search")).thenReturn(handler);
        ToolInvokeRequest request = request();
        ServiceJwtBinding binding = new ServiceJwtBinding(7L, 8L, null, request.runId().toString(),
                request.executionId().toString(), request.nodeId(), request.invocationId().toString());
        ServiceJwtIssuer issuer = new ServiceJwtIssuer(SECRET, "ylcloud-workflow", "ylcloud-workflow", 300);
        ServiceJwtVerifier verifier = new ServiceJwtVerifier(SECRET, "", 0,
                "ylcloud-workflow", "ylcloud-workflow", 300, 30);
        InternalWorkflowToolController controller = new InternalWorkflowToolController(service, verifier);
        String valid = issuer.issue(ServiceJwtAudience.TOOL_GATEWAY, Set.of("tool.web.search"), binding, 120);

        controller.invoke("Bearer " + valid, request);
        verify(service).invoke(request);

        String wrongScope = issuer.issue(ServiceJwtAudience.TOOL_GATEWAY, Set.of("tool.memory.read"), binding, 120);
        assertThrows(BaseException.class, () -> controller.invoke("Bearer " + wrongScope, request));
        String wrongBinding = issuer.issue(ServiceJwtAudience.TOOL_GATEWAY, Set.of("tool.web.search"),
                new ServiceJwtBinding(99L, 8L, null, request.runId().toString(), request.executionId().toString(),
                        request.nodeId(), request.invocationId().toString()), 120);
        assertThrows(BaseException.class, () -> controller.invoke("Bearer " + wrongBinding, request));
    }

    private ToolInvokeRequest request() {
        return new ToolInvokeRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), "node_1", UUID.randomUUID(),
                7, 8, "web.search", RiskLevel.READ_ONLY, Map.of("query", "hello"), null);
    }
}
