package com.ylcloud.gateway;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionFilterTest {
    @Test void redisFailureRejectsBeforeUpstream() {
        var limiter = mock(SlidingWindowLimiter.class);
        when(limiter.acquire(anyString())).thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));
        var settings = ClientAddressTest.settings(List.of());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/file/multipart/chunk")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 1000)));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        new AdmissionFilter(limiter, new ClientAddress(settings), settings).filter(exchange, chain).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }
    @Test void rejectedRequestReceivesRetryAfterWithoutUpstreamCall() {
        var limiter = mock(SlidingWindowLimiter.class);
        when(limiter.acquire(anyString())).thenReturn(Mono.just(new SlidingWindowLimiter.Decision(false, 35)));
        var settings = ClientAddressTest.settings(List.of());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/async/list")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 1000)));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        new AdmissionFilter(limiter, new ClientAddress(settings), settings).filter(exchange, chain).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("35", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        verifyNoInteractions(chain);
    }
}
