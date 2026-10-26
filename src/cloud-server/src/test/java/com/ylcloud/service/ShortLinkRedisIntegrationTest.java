package com.ylcloud.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.ylcloud.interceptor.ShortLinkAbuseInterceptor;
import com.ylcloud.utils.HashUtil;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHORT_LINK_TEST_REDIS_PORT",matches=".+")
class ShortLinkRedisIntegrationTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    @BeforeEach void connect() {
        int port = Integer.parseInt(System.getenv("SHORT_LINK_TEST_REDIS_PORT"));
        if (port != 16389) throw new IllegalArgumentException("Dedicated test Redis port 16389 required");
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }
    @AfterEach void disconnect() { factory.destroy(); }

    @Test void cacheIsSharedBetweenInstancesAndMissingTtlIsBounded() {
        String prefix = "test:short-link:" + UUID.randomUUID() + ":";
        var first = new ShortLinkCache(redis, new SimpleMeterRegistry(), prefix);
        var second = new ShortLinkCache(redis, new SimpleMeterRegistry(), prefix);
        first.put("code:abc", "/api/space/1/files/2/preview");
        assertEquals("/api/space/1/files/2/preview", second.get("code:abc"));
        first.put("code:missing", ShortLinkCache.MISSING);
        assertTrue(redis.getExpire(prefix + "code:missing") <= 5);
        first.evict("code:abc");
        assertNull(second.get("code:abc"));
    }

    @Test void missesAcrossInstancesTemporarilyBlockOnlyTheSameIp() throws Exception {
        String ip = "198.51.100.42";
        String tag = HashUtil.sha256(ip);
        redis.delete(java.util.List.of("ylcloud:short-link:abuse:{"+tag+"}:count", "ylcloud:short-link:abuse:{"+tag+"}:ban"));
        var one = new ShortLinkAbuseGuard(redis, new SimpleMeterRegistry(), 10, 60, 1);
        var two = new ShortLinkAbuseGuard(redis, new SimpleMeterRegistry(), 10, 60, 1);
        var pool = Executors.newFixedThreadPool(5);
        try {
            for (var result : pool.invokeAll(IntStream.range(0,10).mapToObj(i -> (Callable<Long>) () -> one.missing(ip)).toList())) result.get();
        } finally { pool.shutdownNow(); }
        assertEquals(1, two.check(ip));
        assertEquals(0, two.check("198.51.100.43"));
        var request = new MockHttpServletRequest(); request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        assertFalse(new ShortLinkAbuseInterceptor(two).preHandle(request,response,new Object()));
        assertEquals(502,response.getStatus()); assertEquals("1",response.getHeader("Retry-After"));
        Thread.sleep(1100);
        assertEquals(0, two.check(ip));
    }
}
