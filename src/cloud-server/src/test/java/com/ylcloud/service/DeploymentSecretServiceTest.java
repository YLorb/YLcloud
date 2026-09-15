package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.DeploymentSecretVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeploymentSecretServiceTest {
    @TempDir
    Path directory;

    @Test
    void writesProviderSecretWithoutReturningItsValue() throws Exception {
        SecurityAuditService audit = mock(SecurityAuditService.class);
        DeploymentSecretService service = new DeploymentSecretService(directory.toString(), audit);

        DeploymentSecretVO status = service.update("llmApiKey", "sk-local-only");

        assertEquals("sk-local-only", Files.readString(directory.resolve("llm_api_key"), StandardCharsets.UTF_8));
        assertTrue(status.configured());
        assertEquals("llmApiKey", status.key());
        verify(audit).recordCritical(org.mockito.ArgumentMatchers.any(SecurityAuditService.AuditEventBuilder.class));
    }

    @Test
    void rejectsUnknownProtectedAndMultilineValues() {
        DeploymentSecretService service = new DeploymentSecretService(directory.toString(), mock(SecurityAuditService.class));

        assertThrows(BaseException.class, () -> service.update("../outside", "secret"));
        assertThrows(BaseException.class, () -> service.update("jwtSecret", "replacement"));
        assertThrows(BaseException.class, () -> service.update("llmApiKey", "line-one\nline-two"));
    }
}
