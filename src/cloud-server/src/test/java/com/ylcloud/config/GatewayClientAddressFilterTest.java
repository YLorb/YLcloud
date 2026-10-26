package com.ylcloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class GatewayClientAddressFilterTest {
    private static final String SECRET = "synthetic-gateway-secret-for-tests-32";
    @Test void onlyVerifiedGatewayAddressReachesLoginLimiter() throws Exception {
        var filter = new GatewayClientAddressFilter(SECRET);
        for (String proof : new String[]{"spoof", SECRET}) {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("172.20.0.2");
            request.addHeader("X-YLCloud-Client-IP", "192.0.2.1");
            request.addHeader("X-YLCloud-Gateway-Proof", proof);
            request.addHeader("X-Forwarded-For", "203.0.113.1");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    assertEquals(proof.equals(SECRET) ? "192.0.2.1" : "172.20.0.2", req.getRemoteAddr()));
        }
    }
    @Test void unconfiguredBackendNeverTrustsProof() throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-YLCloud-Client-IP", "192.0.2.1"); request.addHeader("X-YLCloud-Gateway-Proof", SECRET);
        new GatewayClientAddressFilter("").doFilter(request, new MockHttpServletResponse(),
                (req, res) -> assertEquals("127.0.0.1", req.getRemoteAddr()));
    }
}
