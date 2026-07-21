package com.ylcloud.workflow.client;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Java → Workflow 内部客户端的资源、超时和有限重试配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "ylcloud.workflow.client")
public class WorkflowClientProperties {
    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8003";
    private int connectTimeoutMs = 2_000;
    private int readTimeoutMs = 5_000;
    private int maxConnections = 32;
    private int maxConnectionsPerRoute = 16;
    private int retryBackoffMs = 50;
    private int maxResponseBytes = 2_097_152;

    @PostConstruct
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank() || !baseUrl.matches("^https?://[^/]+(?::\\d+)?$")) {
            throw new IllegalArgumentException("workflow client base URL must be an origin without a path");
        }
        if (connectTimeoutMs < 100 || connectTimeoutMs > 30_000
                || readTimeoutMs < 100 || readTimeoutMs > 300_000) {
            throw new IllegalArgumentException("workflow client timeout is outside the safe range");
        }
        if (maxConnections < 1 || maxConnections > 256
                || maxConnectionsPerRoute < 1 || maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException("workflow client connection pool is invalid");
        }
        if (retryBackoffMs < 0 || retryBackoffMs > 5_000) {
            throw new IllegalArgumentException("workflow client retry backoff is invalid");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 8_388_608) {
            throw new IllegalArgumentException("workflow client response limit is invalid");
        }
    }
}
