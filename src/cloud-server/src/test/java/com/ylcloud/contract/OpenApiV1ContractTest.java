package com.ylcloud.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiV1ContractTest {
    @Test
    void documentPinsVersionAndExcludesAdministrativeOperations() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while(root != null && !Files.exists(root.resolve("docs/openapi-v1.yaml"))) root = root.getParent();
        assertTrue(root != null);
        String yaml = Files.readString(root.resolve("docs/openapi-v1.yaml"));
        assertTrue(yaml.contains("url: /api/v1"));
        assertTrue(yaml.contains("/files/upload:"));
        assertTrue(yaml.contains("/spaces/{spaceId}/knowledge/query:"));
        assertTrue(yaml.contains("/assistant/sessions/{sessionId}/queries:"));
        assertTrue(yaml.contains("/agent/sessions/{sessionId}/queries:"));
        assertFalse(yaml.contains("/admin"));
        assertFalse(yaml.contains("permanent-delete"));
    }
}
