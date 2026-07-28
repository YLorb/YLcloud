package com.ylcloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AuditQueryDTO;
import com.ylcloud.entity.AuditRetentionConfig;
import com.ylcloud.entity.SecurityAuditEvent;
import com.ylcloud.mapper.SecurityAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityAuditServiceTest {
    @Test
    void criticalEventHasTraceAndRecursivelyRedactsSecrets() throws Exception {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        ObjectMapper json = new ObjectMapper();
        SecurityAuditService service = new SecurityAuditService(mapper, json);

        service.recordCritical(new SecurityAuditService.AuditEventBuilder()
                .eventType("TEST").action("WRITE").subject(7L, "user")
                .target("RESOURCE", "9", "target").result("SUCCESS")
                .detail(Map.of("nested", List.of(Map.of("apiKey", "plain-secret")),
                        "message", "token=plain-token")));

        ArgumentCaptor<SecurityAuditEvent> event = ArgumentCaptor.forClass(SecurityAuditEvent.class);
        verify(mapper).insert(event.capture());
        assertEquals("PERMANENT", event.getValue().getRetentionPolicy());
        assertFalse(event.getValue().getTraceId().isBlank());
        JsonNode detail = json.readTree(event.getValue().getDetailJson());
        assertEquals("[REDACTED]", detail.at("/nested/0/apiKey").asText());
        assertEquals("token=[REDACTED]", detail.get("message").asText());
    }

    @Test
    void auditStorageFailureNeverBypassesCallingBusinessDecision() {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        when(mapper.insert(any())).thenThrow(new IllegalStateException("database unavailable"));
        SecurityAuditService service = new SecurityAuditService(mapper, new ObjectMapper());

        assertDoesNotThrow(() -> service.recordDenied("AUTH", "DENY", 7L, null,
                "RESOURCE", "9", null, "token=secret"));
    }

    @Test
    void cleanupBatchesOnlyThroughStandardRetentionMapperContract() {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        AuditRetentionConfig config = new AuditRetentionConfig();
        config.setRetentionDays(7);
        when(mapper.getRetentionConfig("DEFAULT")).thenReturn(config);
        when(mapper.cleanupExpired(any(), eq(500))).thenReturn(500, 2);
        SecurityAuditService service = new SecurityAuditService(mapper, new ObjectMapper());

        service.cleanupExpiredEvents();

        verify(mapper, times(2)).cleanupExpired(any(), eq(500));
    }

    @Test
    void permanentRetentionCannotBeDowngraded() {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        AuditRetentionConfig config = new AuditRetentionConfig();
        config.setConfigKey("SECURITY_CRITICAL");
        config.setPermanent(true);
        when(mapper.getRetentionConfig("SECURITY_CRITICAL")).thenReturn(config);
        SecurityAuditService service = new SecurityAuditService(mapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRetentionConfig("SECURITY_CRITICAL", 7, false, "bad"));
    }

    @Test
    void partialRetentionUpdatePreservesPermanentFlagAndDescription() {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        AuditRetentionConfig existing = new AuditRetentionConfig();
        existing.setConfigKey("SECURITY_CRITICAL");
        existing.setRetentionDays(365);
        existing.setPermanent(true);
        existing.setDescription("critical events");
        when(mapper.getRetentionConfig("SECURITY_CRITICAL")).thenReturn(existing);
        SecurityAuditService service = new SecurityAuditService(mapper, new ObjectMapper());

        service.updateRetentionConfig("SECURITY_CRITICAL", 730, null, null);

        verify(mapper).updateRetentionConfig(eq("SECURITY_CRITICAL"), eq(730),
                eq(true), eq("critical events"), any());
    }

    @Test
    void unfilteredAdminQueryReturnsAllEventTypesInWindow() {
        SecurityAuditMapper mapper = mock(SecurityAuditMapper.class);
        when(mapper.listByTime(any(), any(), eq(100))).thenReturn(List.of());
        SecurityAuditService service = new SecurityAuditService(mapper, new ObjectMapper());

        service.query(new AuditQueryDTO());

        verify(mapper).listByTime(any(), any(), eq(100));
    }
}
