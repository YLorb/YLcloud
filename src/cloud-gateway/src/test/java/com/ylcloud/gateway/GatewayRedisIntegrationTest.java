package com.ylcloud.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GATEWAY_TEST_REDIS_PORT", matches="\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayRedisIntegrationTest {
    private final String prefix = "gateway-it:" + UUID.randomUUID() + ":";
    private final AtomicInteger upstreamRequests = new AtomicInteger();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpServer upstream;
    private ExecutorService upstreamPool;
    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private ConfigurableApplicationContext first, second;
    private int redisPort;
    private volatile CountDownLatch streamRelease;

    @BeforeAll void start() throws Exception {
        redisPort = Integer.parseInt(System.getenv("GATEWAY_TEST_REDIS_PORT"));
        factory = new LettuceConnectionFactory("127.0.0.1", redisPort);
        factory.afterPropertiesSet(); factory.start();
        redis = new ReactiveStringRedisTemplate(factory);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamPool = Executors.newCachedThreadPool(); upstream.setExecutor(upstreamPool);
        upstream.createContext("/", exchange -> {
            upstreamRequests.incrementAndGet();
            var headers = exchange.getResponseHeaders();
            headers.add("Set-Cookie", "ylcloud_session=test; HttpOnly; Path=/; SameSite=Lax");
            headers.add("Set-Cookie", "other=value; Path=/");
            headers.add("Content-Type", "application/octet-stream");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/broken")) { exchange.close(); return; }
            if (path.equals("/api/stream")) {
                headers.set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("first".getBytes()); output.flush();
                    try { streamRelease.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    output.write("last".getBytes());
                }
                return;
            }
            byte[] body = path.equals("/api/file/upload") ? exchange.getRequestBody().readAllBytes()
                    : (path + "|" + exchange.getRequestHeaders().getFirst("Cookie") + "|"
                    + exchange.getRequestHeaders().getFirst("X-YLCloud-Client-IP") + "|"
                    + exchange.getRequestHeaders().getFirst("X-YLCloud-Gateway-Proof")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.equals("/api/denied") ? 401 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        first = startGateway(redisPort); second = startGateway(redisPort);
    }
    private ConfigurableApplicationContext startGateway(int port) {
        return new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0", "--management.server.port=0", "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + port, "--spring.data.redis.password=",
                "--ylcloud.gateway.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                "--ylcloud.gateway.forwarding-secret=synthetic-test-forwarding-secret-32",
                "--ylcloud.gateway.trusted-proxies=", "--ylcloud.gateway.key-prefix=" + prefix,
                "--ylcloud.gateway.session.enabled=false", "--management.health.r2dbc.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
    @BeforeEach void clearOwnKeys() {
        redis.keys(prefix + "*").flatMap(redis::delete).then().block();
        redis.execute(RedisScript.of("local t=redis.call('TIME'); return redis.call('SET', KEYS[1], tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)-60001)", String.class),
                List.of(prefix + "{admission}:initialized")).then().block();
        upstreamRequests.set(0);
    }
    private URI uri(ConfigurableApplicationContext context, String path) {
        return URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + path);
    }
    @Test void twoGatewaysAndDifferentRoutesShareExactlySixtyAdmissions() {
        var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 100; i++) futures.add(client.sendAsync(HttpRequest.newBuilder(
                uri(i % 2 == 0 ? first : second, i % 3 == 0 ? "/api/file/list" : "/api/async/list"))
                .header("X-Real-IP", "198.51.100." + i).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString()));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertEquals(60, futures.stream().filter(f -> f.join().statusCode() == 200).count());
        assertEquals(40, futures.stream().filter(f -> f.join().statusCode() == 429).count());
        assertEquals(60, upstreamRequests.get());
        assertTrue(futures.stream().filter(f -> f.join().statusCode() == 429)
                .allMatch(f -> Integer.parseInt(f.join().headers().firstValue("Retry-After").orElseThrow()) > 0));
    }
    @Test void redisHistoryLossFailsClosedBeforeAnyUpstreamRequest() throws Exception {
        redis.delete(prefix + "{admission}:initialized").block();
        assertEquals(503, client.send(HttpRequest.newBuilder(uri(first, "/api/login")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, upstreamRequests.get());
    }
    @Test void realRedisTimeoutReturns503WithoutSendingToUpstream() throws Exception {
        try (var connection = factory.getConnection()) {
            connection.execute("CLIENT", "PAUSE".getBytes(), "3000".getBytes(), "ALL".getBytes());
        }
        try {
            var response = client.send(HttpRequest.newBuilder(uri(first, "/api/file/upload"))
                    .POST(HttpRequest.BodyPublishers.ofString("must-not-reach-upstream")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode()); assertEquals(0, upstreamRequests.get());
        } finally { Thread.sleep(3100); }
    }
    @Test void oldAdmissionsExpireWithoutFixedMinuteBoundaryBurst() {
        var limiter = first.getBean(SlidingWindowLimiter.class);
        for (int i=0; i<60; i++) assertTrue(limiter.acquire("192.0.2.1").block().allowed());
        assertFalse(limiter.acquire("192.0.2.1").block().allowed());
        String key = redis.keys(prefix + "{admission}:*").filter(k -> !k.endsWith(":initialized")).single().block();
        redis.execute(RedisScript.of("local t=redis.call('TIME'); local old=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)-60001; local m=redis.call('ZRANGE',KEYS[1],0,0); redis.call('ZADD',KEYS[1],old,m[1]); return 1", Long.class), List.of(key)).then().block();
        assertTrue(limiter.acquire("192.0.2.1").block().allowed());
        assertFalse(limiter.acquire("192.0.2.1").block().allowed());
        assertTrue(limiter.acquire("192.0.2.2").block().allowed());
    }
    @Test void cookiesUnauthorizedResponsesAndUploadsSurviveProxy() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(first, "/api/denied"))
                .header("Cookie", "ylcloud_session=fixture").header("X-YLCloud-Gateway-Proof", "spoof")
                .header("X-YLCloud-Client-IP", "198.51.100.42").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(2, response.headers().allValues("Set-Cookie").size());
        assertTrue(response.body().contains("ylcloud_session=fixture|127.0.0.1|synthetic-test-forwarding-secret-32"));
        byte[] bytes = new byte[2 * 1024 * 1024]; new Random(42).nextBytes(bytes);
        var uploaded = client.send(HttpRequest.newBuilder(uri(first, "/api/file/upload"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, uploaded.statusCode()); assertArrayEquals(bytes, uploaded.body());
    }
    @Test void internalAndActuatorRoutesAreNotExposed() throws Exception {
        for (String path : List.of("/internal/v1/tools", "/actuator/env", "/model/embed", "/workflow/run", "/parser/parse/ocr"))
            assertEquals(404, client.send(HttpRequest.newBuilder(uri(first, path)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, upstreamRequests.get());
    }
    @Test void streamingResponseArrivesBeforeUpstreamCompletes() throws Exception {
        streamRelease = new CountDownLatch(1);
        try (var body = client.send(HttpRequest.newBuilder(uri(first, "/api/stream")).build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
            var firstChunk = CompletableFuture.supplyAsync(() -> {
                try { return body.readNBytes(5); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            try { assertArrayEquals("first".getBytes(), firstChunk.get(2,TimeUnit.SECONDS)); }
            finally { streamRelease.countDown(); }
            assertArrayEquals("last".getBytes(), body.readAllBytes());
        }
    }
    @Test void disconnectedUpstreamReturns502InsteadOfRedis503() throws Exception {
        assertEquals(502, client.send(HttpRequest.newBuilder(uri(first, "/api/broken")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @AfterAll void stop() {
        if (second != null) second.close(); if (first != null) first.close();
        if (upstream != null) upstream.stop(0); if (upstreamPool != null) upstreamPool.shutdownNow();
        if (redis != null) redis.keys(prefix + "*").flatMap(redis::delete).then().block();
        if (factory != null) factory.destroy();
    }
}
