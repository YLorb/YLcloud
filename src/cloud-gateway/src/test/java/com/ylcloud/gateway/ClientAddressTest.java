package com.ylcloud.gateway;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.junit.jupiter.api.Assertions.*;

class ClientAddressTest {
    static GatewaySettings settings(List<String> trusted) {
        return new GatewaySettings(null, trusted, "synthetic-test-forwarding-secret-32", Duration.ofSeconds(1), "test:");
    }
    @Test void ignoresSpoofedHeadersFromUntrustedPeer() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/file/list")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 1000))
                .header("X-Real-IP", "198.51.100.2").header("X-Forwarded-For", "198.51.100.3"));
        assertEquals("192.0.2.1", new ClientAddress(settings(List.of())).resolve(exchange));
    }
    @Test void trustedEdgeMustProvideOneLiteralAddress() {
        var resolver = new ClientAddress(settings(List.of("127.0.0.1")));
        var valid = MockServerWebExchange.from(MockServerHttpRequest.get("/api/file/list")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 1000)).header("X-Real-IP", "2001:db8::1"));
        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(valid));
        var invalid = MockServerWebExchange.from(MockServerHttpRequest.get("/api/file/list")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 1000)).header("X-Real-IP", "1.2.3.4, 5.6.7.8"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(invalid));
    }
    @Test void mappedIpv4CannotCreateAnotherBudget() {
        assertEquals(ClientAddress.normalize("192.0.2.1"), ClientAddress.normalize("::ffff:192.0.2.1"));
    }
}
