package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunAccepted;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunCreateRequest;
import com.ylcloud.workflow.security.ServiceJwtAudience;
import com.ylcloud.workflow.security.ServiceJwtBinding;
import com.ylcloud.workflow.security.ServiceJwtIssuer;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 带连接池、短超时、Service JWT 和稳定错误分类的 Workflow 客户端。
 * create 使用同一幂等键最多重试一次；GET 最多重试两次；retry execution 绝不自动重放。
 */
@Component
public class WorkflowHttpClient {
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);
    private final WorkflowClientProperties properties;
    private final ServiceJwtIssuer jwtIssuer;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    public WorkflowHttpClient(
            WorkflowClientProperties properties,
            ServiceJwtIssuer jwtIssuer,
            ObjectMapper objectMapper
    ) {
        properties.validate();
        this.properties = properties;
        this.jwtIssuer = jwtIssuer;
        this.objectMapper = objectMapper;
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create().build();
        pool.setMaxTotal(properties.getMaxConnections());
        pool.setDefaultMaxPerRoute(properties.getMaxConnectionsPerRoute());
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeoutMs()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getConnectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.getReadTimeoutMs()))
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .build();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public WorkflowRunAccepted createRun(WorkflowRunCreateRequest request, String idempotencyKey) {
        ServiceJwtBinding binding = ServiceJwtBinding.runCreate(
                request.userId(), request.sessionId(), request.assistantMessageId()
        );
        return withRetry(() -> executeJson(
                "POST", "/internal/v1/workflow-runs", request,
                token("workflow.run.create", binding), idempotencyKey,
                Set.of(202), WorkflowRunAccepted.class
        ), 2);
    }

    public WorkflowRunStatusResponse getRun(UUID runId) {
        return withRetry(() -> executeJson(
                "GET", "/internal/v1/workflow-runs/" + runId, null,
                token("workflow.run.read", ServiceJwtBinding.run(runId.toString())), null,
                Set.of(200), WorkflowRunStatusResponse.class
        ), 3);
    }

    public WorkflowResult getResult(UUID runId) {
        return withRetry(() -> executeJson(
                "GET", "/internal/v1/workflow-runs/" + runId + "/result", null,
                token("workflow.run.read", ServiceJwtBinding.run(runId.toString())), null,
                Set.of(200), WorkflowResult.class
        ), 3);
    }

    public WorkflowOperationResponse cancelRun(UUID runId) {
        return withRetry(() -> executeJson(
                "POST", "/internal/v1/workflow-runs/" + runId + "/cancel", null,
                token("workflow.run.cancel", ServiceJwtBinding.run(runId.toString())),
                "cancel:" + runId, Set.of(200), WorkflowOperationResponse.class
        ), 2);
    }

    public WorkflowRunAccepted retryRun(UUID runId, String retryKey) {
        // Workflow retry 会创建新 execution；在服务端支持 retry 幂等前，客户端禁止自动重放。
        return executeJson(
                "POST", "/internal/v1/workflow-runs/" + runId + "/retry", null,
                token("workflow.run.retry", ServiceJwtBinding.run(runId.toString())), retryKey,
                Set.of(202), WorkflowRunAccepted.class
        );
    }

    private String token(String scope, ServiceJwtBinding binding) {
        return jwtIssuer.issue(ServiceJwtAudience.WORKFLOW, Set.of(scope), binding, 120);
    }

    private <T> T executeJson(
            String method,
            String path,
            Object body,
            String token,
            String idempotencyKey,
            Set<Integer> expectedStatuses,
            Class<T> responseType
    ) {
        HttpUriRequestBase request = new HttpUriRequestBase(
                method, URI.create(properties.getBaseUrl() + path)
        );
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.setHeader("X-Request-Id", UUID.randomUUID().toString());
        request.setHeader("traceparent", traceparent());
        if (idempotencyKey != null) request.setHeader("Idempotency-Key", idempotencyKey);
        try {
            if (body != null) {
                String json = objectMapper.writeValueAsString(body);
                request.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            }
            return httpClient.execute(request, response -> {
                int status = response.getCode();
                if (!expectedStatuses.contains(status)) {
                    throw statusError(status);
                }
                if (response.getEntity() == null) {
                    throw new WorkflowClientException(
                            "WORKFLOW_EMPTY_RESPONSE", "workflow returned an empty response", true, status
                    );
                }
                byte[] payload = EntityUtils.toByteArray(
                        response.getEntity(), properties.getMaxResponseBytes()
                );
                return objectMapper.readValue(payload, responseType);
            });
        } catch (WorkflowClientException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkflowClientException(
                    "WORKFLOW_TRANSPORT_ERROR", "workflow service is unavailable", true, 0
            );
        }
    }

    private WorkflowClientException statusError(int status) {
        boolean retryable = RETRYABLE_STATUSES.contains(status) || status == 429;
        return new WorkflowClientException(
                "WORKFLOW_HTTP_" + status,
                retryable ? "workflow service is temporarily unavailable" : "workflow request was rejected",
                retryable,
                status
        );
    }

    private <T> T withRetry(Supplier<T> action, int maxAttempts) {
        WorkflowClientException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (WorkflowClientException exception) {
                last = exception;
                if (!exception.isRetryable() || attempt == maxAttempts) throw exception;
                backoff(attempt);
            }
        }
        throw last;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep((long) properties.getRetryBackoffMs() * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkflowClientException(
                    "WORKFLOW_CLIENT_INTERRUPTED", "workflow request was interrupted", true, 0
            );
        }
    }

    private String traceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }
}
