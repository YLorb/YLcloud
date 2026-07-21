package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.DTO.WorkflowConfirmationCreateDTO;
import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationGrant;
import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WorkflowConfirmationServiceTest {
    @Test
    void allowOnceIsAtomicallyConsumedAndCannotBeReused() {
        WorkflowConfirmationMapper mapper = mock(WorkflowConfirmationMapper.class);
        UUID grantId = UUID.randomUUID(); Instant issued = Instant.now().minusSeconds(1);
        ConfirmationGrant grant = new ConfirmationGrant(ConfirmationMode.ALLOW_ONCE, grantId, 7,
                "memory.delete", "a".repeat(64), null, issued, issued.plusSeconds(60));
        when(mapper.getForUpdate(grantId.toString())).thenReturn(record(grant));
        when(mapper.consumeOnce(eq(grantId.toString()), anyString())).thenReturn(1, 0);
        WorkflowConfirmationService service = service(mapper);
        ToolInvocationContext context = new ToolInvocationContext(UUID.randomUUID(), UUID.randomUUID(), "node",
                UUID.randomUUID(), 7, 8);

        service.authorize(grant, context, "memory.delete", "a".repeat(64), Map.of("memoryId", 5));
        assertThrows(BaseException.class, () -> service.authorize(grant, context, "memory.delete",
                "a".repeat(64), Map.of("memoryId", 5)));
    }

    @Test
    void allowSimilarCannotEscapePersistedParameterBoundary() throws Exception {
        WorkflowConfirmationMapper mapper = mock(WorkflowConfirmationMapper.class);
        UUID grantId = UUID.randomUUID(); Instant issued = Instant.now().minusSeconds(1);
        ConfirmationGrant grant = new ConfirmationGrant(ConfirmationMode.ALLOW_SIMILAR, grantId, 7,
                "smtp.send_email", "b".repeat(64), Map.of("to", "team@example.com"), issued, issued.plusSeconds(60));
        WorkflowConfirmationRecord record = record(grant);
        record.setSimilarityScopeJson(new ObjectMapper().writeValueAsString(grant.similarityScope()));
        when(mapper.getForUpdate(grantId.toString())).thenReturn(record);
        WorkflowConfirmationService service = service(mapper);
        ToolInvocationContext context = new ToolInvocationContext(UUID.randomUUID(), UUID.randomUUID(), "node",
                UUID.randomUUID(), 7, 8);

        service.authorize(grant, context, "smtp.send_email", "different", Map.of("to", "team@example.com", "body", "one"));
        assertThrows(BaseException.class, () -> service.authorize(grant, context, "smtp.send_email", "different",
                Map.of("to", "attacker@example.com", "body", "two")));
    }

    @Test
    void issuesOnlyHighRiskGrantAndPersistsCanonicalParameterHash() {
        WorkflowConfirmationMapper mapper = mock(WorkflowConfirmationMapper.class);
        WorkflowToolRegistry registry = mock(WorkflowToolRegistry.class);
        WorkflowToolHandler handler = mock(WorkflowToolHandler.class);
        when(registry.require("smtp.send_email")).thenReturn(handler);
        when(handler.riskLevel()).thenReturn(RiskLevel.HIGH);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowConfirmationService service = new WorkflowConfirmationService(mapper, objectMapper, registry,
                new ToolArgumentsCanonicalizer(objectMapper));
        WorkflowConfirmationCreateDTO dto = new WorkflowConfirmationCreateDTO(); dto.setToolName("smtp.send_email");
        dto.setMode(ConfirmationMode.ALLOW_SIMILAR); dto.setArguments(Map.of("to", "team@example.com", "body", "hello"));
        dto.setSimilarityScope(Map.of("to", "team@example.com")); dto.setTtlSeconds(60);

        ConfirmationGrant grant = service.issue(7, dto);

        assertEquals("smtp.send_email", grant.toolName());
        assertEquals(64, grant.parameterHash().length());
        verify(mapper).insert(any());
    }

    private WorkflowConfirmationRecord record(ConfirmationGrant grant) {
        WorkflowConfirmationRecord value = new WorkflowConfirmationRecord(); value.setGrantId(grant.grantId().toString());
        value.setUserId(grant.userId()); value.setToolName(grant.toolName()); value.setParameterHash(grant.parameterHash());
        value.setGrantMode(grant.mode().name()); value.setExpiresAt(LocalDateTime.ofInstant(grant.expiresAt(), ZoneId.systemDefault()));
        value.setRevoked(false); return value;
    }

    private WorkflowConfirmationService service(WorkflowConfirmationMapper mapper) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new WorkflowConfirmationService(mapper, objectMapper, mock(WorkflowToolRegistry.class),
                new ToolArgumentsCanonicalizer(objectMapper));
    }
}
