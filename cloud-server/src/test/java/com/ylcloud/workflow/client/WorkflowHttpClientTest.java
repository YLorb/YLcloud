package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ylcloud.workflow.contract.WorkflowContracts.KnowledgeScopeDecision;
import com.ylcloud.workflow.contract.WorkflowContracts.RunBudget;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunCreateRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowType;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowVersion;
import com.ylcloud.workflow.security.ServiceJwtIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowHttpClientTest {
    private static final String SECRET = "active-service-secret-32-bytes-minimum-0001";
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private HttpServer server;
    private WorkflowHttpClient client;
    private String createIdempotency;
    private String createAuthorization;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/workflow-runs", this::handle);
        server.start();
        WorkflowClientProperties properties = new WorkflowClientProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRetryBackoffMs(0);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        client = new WorkflowHttpClient(
                properties,
                new ServiceJwtIssuer(SECRET, "ylcloud-app", "ylcloud-app", 300),
                mapper
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    void createRetriesOnceWithSameIdempotencyAndReturnsOnlyAfter202() {
        var accepted = client.createRun(request(), "assistant:302:hash:v2");
        assertEquals(2, creates.get());
        assertEquals("11111111-1111-4111-8111-111111111111", accepted.runId().toString());
        assertEquals("assistant:302:hash:v2", createIdempotency);
        assertNotNull(createAuthorization);
        assertEquals(true, createAuthorization.startsWith("Bearer "));
    }

    @Test
    void getCancelAndRetryUseExpectedContractsAndRetryExecutionIsNotAutoReplayed() {
        var runId = java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");
        assertEquals("RUNNING", client.getRun(runId).status().name());
        assertEquals("CANCELLED", client.cancelRun(runId).status().name());
        WorkflowClientException failure = assertThrows(
                WorkflowClientException.class,
                () -> client.retryRun(runId, "retry-1")
        );
        assertEquals(503, failure.getHttpStatus());
        assertEquals(1, retries.get());
    }

    @Test
    void rejectsUnsafeClientConfiguration() {
        WorkflowClientProperties properties = new WorkflowClientProperties();
        properties.setBaseUrl("http://127.0.0.1:8003/user-controlled/path");
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/internal/v1/workflow-runs".equals(path)) {
            createIdempotency = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            createAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (creates.incrementAndGet() == 1) {
                send(exchange, 503, "{}");
                return;
            }
            send(exchange, 202, """
                    {"contractVersion":"1.0","runId":"11111111-1111-4111-8111-111111111111",
                    "executionId":"22222222-2222-4222-8222-222222222222","executionEpoch":1,
                    "status":"QUEUED","acceptedAt":"2026-07-22T00:00:00Z"}
                    """);
            return;
        }
        if (path.endsWith("/cancel")) {
            send(exchange, 200, """
                    {"contractVersion":"1.0","runId":"11111111-1111-4111-8111-111111111111","status":"CANCELLED"}
                    """);
            return;
        }
        if (path.endsWith("/retry")) {
            retries.incrementAndGet();
            send(exchange, 503, "{}");
            return;
        }
        send(exchange, 200, """
                {"contractVersion":"1.0","runId":"11111111-1111-4111-8111-111111111111",
                "executionId":"22222222-2222-4222-8222-222222222222","executionEpoch":1,
                "status":"RUNNING","createdAt":"2026-07-22T00:00:00Z","updatedAt":"2026-07-22T00:00:01Z"}
                """);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private WorkflowRunCreateRequest request() {
        return new WorkflowRunCreateRequest(
                "1.0", WorkflowType.ASSISTANT, WorkflowVersion.V2,
                101, 201, 301, 302, "question", List.of(),
                Map.of("tenant", "default"),
                new KnowledgeScopeDecision(true, List.of(11L), List.of()),
                "assistant-v1", "a".repeat(64), new RunBudget(6, 3, 8, 120_000)
        );
    }
}
