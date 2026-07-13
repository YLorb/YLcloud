package com.ylcloud.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretFileEnvironmentPostProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void fileValueTakesPrecedenceOverPlainEnvironmentValue() throws Exception {
        Path secretFile = tempDir.resolve("jwt_secret");
        Files.writeString(secretFile,"file-secret-value\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("YLCLOUD_JWT_SECRET","plain-value")
                .withProperty("YLCLOUD_JWT_SECRET_FILE",secretFile.toString());

        new SecretFileEnvironmentPostProcessor().postProcessEnvironment(environment,null);

        assertEquals("file-secret-value",environment.getProperty("YLCLOUD_JWT_SECRET"));
    }

    @Test
    void explicitMissingSecretFileFailsStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("YLCLOUD_LLM_API_KEY_FILE",tempDir.resolve("missing").toString());

        assertThrows(IllegalStateException.class,
                () -> new SecretFileEnvironmentPostProcessor().postProcessEnvironment(environment,null));
    }
}
