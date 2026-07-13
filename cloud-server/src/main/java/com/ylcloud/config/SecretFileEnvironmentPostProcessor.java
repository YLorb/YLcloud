package com.ylcloud.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads deployment secrets from files before Spring binds application properties.
 *
 * <p>For every supported {@code NAME}, {@code NAME_FILE} takes precedence over
 * the plain {@code NAME} environment variable. Secret values are never logged.</p>
 */
public class SecretFileEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String PROPERTY_SOURCE_NAME = "ylcloudSecretFiles";
    private static final List<String> SUPPORTED_SECRETS = List.of(
            "YLCLOUD_JWT_SECRET",
            "YLCLOUD_LLM_API_KEY",
            "YLCLOUD_ARK_API_KEY",
            "YLCLOUD_RAG_QUERY_API_KEY",
            "YLCLOUD_VLM_API_KEY"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String,Object> secrets = new LinkedHashMap<>();
        for(String name : SUPPORTED_SECRETS) {
            String fileName = environment.getProperty(name + "_FILE");
            if(fileName == null || fileName.isBlank()) {
                continue;
            }
            secrets.put(name,readSecret(Path.of(fileName),name,"YLCLOUD_JWT_SECRET".equals(name)));
        }
        if(!secrets.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,secrets));
        }
    }

    private String readSecret(Path path, String name, boolean required) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            if(required && value.isEmpty()) {
                throw new IllegalStateException(name + " secret file is empty");
            }
            return value;
        } catch(IOException exception) {
            throw new IllegalStateException("Unable to read secret file for " + name,exception);
        }
    }
}
