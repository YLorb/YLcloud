package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.service.AgentRiskAuthorizationService;
import com.ylcloud.workflow.contract.WorkflowContracts.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowToolInvocationServiceTest {
    @Test
    void executesRegisteredToolOnceAndReplaysPersistedResponse() throws Exception {
        WorkflowToolHandler handler = handler("web.search", RiskLevel.READ_ONLY, "tool.web.search");
        when(handler.invoke(any(), any())).thenReturn(Map.of("ok", true));
        WorkflowToolInvocationMapper mapper = mock(WorkflowToolInvocationMapper.class);
        AtomicReference<WorkflowToolInvocationRecord> stored = new AtomicReference<>();
        when(mapper.get(anyString())).thenAnswer(call -> stored.get());
        when(mapper.insert(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.complete(anyString(), anyString(), isNull())).thenAnswer(call -> {
            stored.get().setInvocationStatus("COMPLETED"); stored.get().setResponseJson(call.getArgument(1)); return 1;
        });
        WorkflowToolInvocationService service = service(handler, mapper, mock(AgentRiskAuthorizationService.class));
        ToolInvokeRequest request = request("web.search", RiskLevel.READ_ONLY, null, 7);

        ToolInvokeResponse first = service.invoke(request);
        ToolInvokeResponse replay = service.invoke(request);

        assertEquals(ToolInvokeResponse.ToolInvokeStatus.SUCCEEDED, first.status());
        assertEquals(first, replay);
        verify(handler, times(1)).invoke(any(), any());
    }

    @Test
    void highRiskUsesDynamicSubjectAuthorizationAndRiskMismatchIsRejected() {
        WorkflowToolHandler handler = handler("memory.delete", RiskLevel.HIGH, "tool.memory.delete");
        when(handler.invoke(any(), any())).thenReturn(Map.of("deleted", true));
        WorkflowToolInvocationMapper mapper = mock(WorkflowToolInvocationMapper.class);
        when(mapper.get(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.complete(anyString(), anyString(), isNull())).thenReturn(1);
        AgentRiskAuthorizationService authorizations = mock(AgentRiskAuthorizationService.class);
        WorkflowToolInvocationService service = service(handler, mapper, authorizations);

        service.invoke(request("memory.delete", RiskLevel.HIGH, 91L, 7));

        verify(authorizations).authorizeHighRisk(eq(7L),eq(91L),anyString());
        assertThrows(BaseException.class, () -> service.invoke(request("memory.delete", RiskLevel.WRITE, null, 7)));
    }

    @Test
    void registryRejectsDuplicateAndUnknownTools() {
        WorkflowToolHandler first = handler("web.search", RiskLevel.READ_ONLY, "tool.web.search");
        assertThrows(IllegalStateException.class, () -> new WorkflowToolRegistry(List.of(first, first)));
        WorkflowToolRegistry registry = new WorkflowToolRegistry(List.of(first));
        assertThrows(BaseException.class, () -> registry.require("system.reflect"));
    }

    private WorkflowToolInvocationService service(WorkflowToolHandler handler, WorkflowToolInvocationMapper mapper,
                                                   AgentRiskAuthorizationService authorizations) {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        return new WorkflowToolInvocationService(new WorkflowToolRegistry(List.of(handler)), mapper, authorizations, objectMapper,
                new ToolArgumentsCanonicalizer(objectMapper));
    }

    private WorkflowToolHandler handler(String name, RiskLevel risk, String scope) {
        WorkflowToolHandler handler = mock(WorkflowToolHandler.class);
        when(handler.name()).thenReturn(name); when(handler.riskLevel()).thenReturn(risk); when(handler.requiredScope()).thenReturn(scope);
        return handler;
    }

    private ToolInvokeRequest request(String name, RiskLevel risk, Long apiKeyId, long userId) {
        return new ToolInvokeRequest("1.0", UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"), "node_1",
                UUID.fromString("33333333-3333-4333-8333-333333333333"), userId, apiKeyId, 8, name, risk,
                Map.of("memoryId", 5, "query", "hello"));
    }
}
