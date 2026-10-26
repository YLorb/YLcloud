package com.ylcloud.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAuthenticationFilterTest {
    private static final String SECRET = "test-only-gateway-identity-secret-32-bytes";
    private static final String COOKIE = "a".repeat(43);
    private final GatewaySessionStore store = mock(GatewaySessionStore.class);
    private final SessionAuthenticationFilter filter = new SessionAuthenticationFilter(store,
            new SessionSettings(true, false, SECRET, Duration.ofMillis(40)));
    @Test void privateRequestWithoutSessionIsRejectedBeforeUpstream() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/file/list"));
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("upstream reached"))).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(store);
    }
    @Test void authenticatedRequestOverwritesSpoofedIdentityAndBindsMethodQueryAndCookie() {
        var expected = new GatewayIdentityCodec.Identity(7,"alice","Alice","USER",false);
        when(store.authenticate(COOKIE)).thenReturn(Mono.just(expected));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/file/list?parentId=2")
                .cookie(new HttpCookie("ylcloud_session", COOKIE)).header(GatewayIdentityCodec.HEADER,"spoof"));
        filter.filter(exchange, forwarded -> {
            String identity = forwarded.getRequest().getHeaders().getFirst(GatewayIdentityCodec.HEADER);
            var codec = new GatewayIdentityCodec(SECRET);
            assertEquals(expected, codec.verify(identity,"GET","/api/file/list?parentId=2",COOKIE));
            assertThrows(IllegalArgumentException.class, () -> codec.verify(identity,"POST","/api/file/list?parentId=2",COOKIE));
            assertThrows(IllegalArgumentException.class, () -> codec.verify(identity,"GET","/api/file/list?parentId=3",COOKIE));
            assertThrows(IllegalArgumentException.class, () -> codec.verify(identity,"GET","/api/file/list?parentId=2","b".repeat(43)));
            return Mono.empty();
        }).block();
        verify(store).authenticate(COOKIE);
    }
    @Test void invalidSessionAndDuplicateCookiesFailClosed() {
        when(store.authenticate(COOKIE)).thenReturn(Mono.empty());
        var expired = MockServerWebExchange.from(MockServerHttpRequest.get("/api/session").cookie(new HttpCookie("ylcloud_session",COOKIE)));
        filter.filter(expired, ignored -> Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.UNAUTHORIZED,expired.getResponse().getStatusCode());
        var duplicate = MockServerWebExchange.from(MockServerHttpRequest.get("/api/session")
                .cookie(new HttpCookie("ylcloud_session",COOKIE),new HttpCookie("ylcloud_session",COOKIE)));
        filter.filter(duplicate, ignored -> Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.UNAUTHORIZED,duplicate.getResponse().getStatusCode());
        verify(store,times(1)).authenticate(COOKIE);
    }
    @Test void databaseFailureAndTimeoutAre503WithoutCallingUpstream() {
        for (Mono<GatewayIdentityCodec.Identity> result : java.util.List.<Mono<GatewayIdentityCodec.Identity>>of(Mono.error(new RuntimeException("db down")),Mono.never())) {
            when(store.authenticate(COOKIE)).thenReturn(result);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/session").cookie(new HttpCookie("ylcloud_session",COOKIE)));
            filter.filter(exchange, ignored -> Mono.error(new AssertionError())).block();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
        }
    }
    @Test void publicAndApiKeyRoutesKeepTheirExistingContract() {
        for (String path : java.util.List.of("/api/login","/api/sign","/api/site/public-settings","/api/v1/files","/api/open/files","/api/share/old")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            var reached = new AtomicBoolean();
            filter.filter(exchange, forwarded -> {
                reached.set(true);
                assertEquals(0,new GatewayIdentityCodec(SECRET).verify(forwarded.getRequest().getHeaders().getFirst(GatewayIdentityCodec.HEADER),"GET",path,null).userId());
                return Mono.empty();
            }).block();
            assertTrue(reached.get());
        }
        verifyNoInteractions(store);
    }
    @Test void publicSharePreservesOptionalAuthenticatedIdentity() {
        when(store.authenticate(COOKIE)).thenReturn(Mono.just(new GatewayIdentityCodec.Identity(9,"member","Member","USER",false)));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/public/share-links/code/open").cookie(new HttpCookie("ylcloud_session",COOKIE)));
        filter.filter(exchange, forwarded -> {
            assertEquals(9,new GatewayIdentityCodec(SECRET).verify(forwarded.getRequest().getHeaders().getFirst(GatewayIdentityCodec.HEADER),"POST","/api/public/share-links/code/open",COOKIE).userId());
            return Mono.empty();
        }).block();
    }
}
