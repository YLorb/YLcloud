package com.ylcloud.config;

import com.ylcloud.security.ApiKeyIdentityCodec;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class GatewayApiKeyIdentityFilterTest {
    private static final String SECRET="test-api-key-identity-secret-at-least-32";
    private final GatewayApiKeyIdentityFilter filter=new GatewayApiKeyIdentityFilter(true,SECRET);
    @Test void missingSpoofedAndDuplicateProofsCannotReachController() throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            var request=new MockHttpServletRequest("GET","/api/v1/files");
            if(scenario>0) request.addHeader(ApiKeyIdentityCodec.HEADER,"spoof");
            if(scenario>1) request.addHeader(ApiKeyIdentityCodec.HEADER,"spoof2");
            var response=new MockHttpServletResponse(); var reached=new AtomicBoolean();
            filter.doFilter(request,response,(a,b)->reached.set(true));
            assertEquals(401,response.getStatus()); assertFalse(reached.get());
        }
    }
    @Test void signedKeyIsBoundToMethodQueryAndServerRequestId() throws Exception {
        var codec=new ApiKeyIdentityCodec(SECRET); String requestId=UUID.randomUUID().toString();
        for(boolean tampered:new boolean[]{false,true}) {
            var request=new MockHttpServletRequest("GET","/api/open/files");
            request.setQueryString(tampered ? "parentId=3" : "parentId=2");
            request.addHeader("X-Request-Id",requestId);
            request.addHeader(ApiKeyIdentityCodec.HEADER,codec.sign(3,7,requestId,"GET","/api/open/files?parentId=2"));
            var response=new MockHttpServletResponse(); var reached=new AtomicBoolean();
            filter.doFilter(request,response,(a,b)->{ reached.set(true); assertEquals(7,((ApiKeyIdentityCodec.Identity)a.getAttribute(GatewayApiKeyIdentityFilter.IDENTITY)).userId()); });
            assertEquals(!tampered,reached.get());
            if(tampered) assertEquals(401,response.getStatus());
        }
    }
}
