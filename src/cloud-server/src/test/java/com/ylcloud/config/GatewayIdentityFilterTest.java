package com.ylcloud.config;

import com.ylcloud.security.GatewayIdentityCodec;
import com.ylcloud.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayIdentityFilterTest {
    private static final String SECRET="test-only-gateway-identity-secret-32-bytes";
    private final JdbcTemplate database=mock(JdbcTemplate.class);
    private final BrowserSessionService sessions=new BrowserSessionService(database,false);
    private final GatewayIdentityFilter filter=new GatewayIdentityFilter(true,SECRET,sessions);
    @Test void signedIdentityIsConsumedWithoutRevalidatingSessionInBackend() throws Exception {
        String cookie="a".repeat(43);
        var request=new MockHttpServletRequest("GET","/api/session");
        request.setCookies(new Cookie("ylcloud_session",cookie));
        request.addHeader(GatewayIdentityCodec.HEADER,new GatewayIdentityCodec(SECRET).sign(new GatewayIdentityCodec.Identity(7,"alice","Alice","USER",false),"GET","/api/session",cookie));
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertEquals(7L,sessions.authenticate(request).getId()));
        verifyNoInteractions(database);
    }
    @Test void directAndForgedRequestsAreRejectedIncludingLoginAndOpenApi() throws Exception {
        for(String path:java.util.List.of("/api/session","/api/login","/api/v1/files")) {
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET",path),response,(req,res)->fail("bypass"));
            assertEquals(401,response.getStatus());
        }
        var forged=new MockHttpServletRequest("GET","/api/session"); forged.addHeader(GatewayIdentityCodec.HEADER,"spoof");
        var response=new MockHttpServletResponse();
        filter.doFilter(forged,response,(req,res)->fail("bypass")); assertEquals(401,response.getStatus());
    }
    @Test void anonymousSignedPublicRequestCannotAcquireUserIdentity() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/session");
        request.addHeader(GatewayIdentityCodec.HEADER,new GatewayIdentityCodec(SECRET).sign(null,"GET","/api/session",null));
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertNull(sessions.authenticate(request)));
        verifyNoInteractions(database);
    }
    @Test void healthReadAndInternalTrafficDoNotRequireGateway() throws Exception {
        for(String path:java.util.List.of("/api/site/public-settings","/internal/v1/tools")) {
            var reached=new AtomicBoolean();
            filter.doFilter(new MockHttpServletRequest("GET",path),new MockHttpServletResponse(),(req,res)->reached.set(true));
            assertTrue(reached.get());
        }
    }
    @Test void matrixParametersCannotBypassGatewayEvenWithSessionCookie() throws Exception {
        for (String path : java.util.List.of("/api;review/session", "/api%3breview/session",
                "/api%3Breview/login", "/api;review/site/public-settings", "/api/session;review")) {
            var request = new MockHttpServletRequest("GET", path);
            request.setCookies(new Cookie("ylcloud_session", "a".repeat(43)));
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req,res) -> fail("matrix path bypass"));
            assertEquals(401, response.getStatus(), path);
        }
        verifyNoInteractions(database);
    }
    @Test void containerNormalizedApiPathsStillRequireSignedIdentity() throws Exception {
        for (String path : java.util.List.of("/%61pi/session", "//api/session", "/internal/../api/session")) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath("/api/session");
            request.setCookies(new Cookie("ylcloud_session", "a".repeat(43)));
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req,res) -> fail("normalized path bypass"));
            assertEquals(401, response.getStatus(), path);
        }
        verifyNoInteractions(database);
    }
    @Test void expiredAndTamperedInternalAssertionsAreRejected() throws Exception {
        var codec = new GatewayIdentityCodec(SECRET);
        String signed = codec.sign(new GatewayIdentityCodec.Identity(7,"alice","Alice","USER",false),"GET","/api/session",null);
        String[] fields = signed.split("\\|", -1);
        fields[1] = Long.toString(java.time.Instant.now().minusSeconds(60).getEpochSecond());
        String oldPayload = String.join("|", java.util.Arrays.copyOf(fields, 9));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expired = oldPayload + "|" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(oldPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String assertion : java.util.List.of(expired, signed.replace("|7|", "|8|"))) {
            var request = new MockHttpServletRequest("GET", "/api/session");
            request.addHeader(GatewayIdentityCodec.HEADER, assertion);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req,res) -> fail("untrusted assertion reached backend"));
            assertEquals(401, response.getStatus());
        }
        verifyNoInteractions(database);
    }
}
