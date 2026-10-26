package com.ylcloud.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.OpenApiVersionPolicyService;
import com.ylcloud.service.UserApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiKeyInterceptorTest {
    @Test void verifiedGatewayIdentityUsesNoPlaintextAndClearsBillingContext() throws Exception {
        UserApiKeyService keys=mock(UserApiKeyService.class);
        var policy=mock(OpenApiVersionPolicyService.class);
        when(keys.authenticateGatewayIdentity(7L,3L)).thenReturn(new ApiKeyPrincipal(3L,7L,"prefix","READ",1L,Set.of("DRIVE_READ"),Set.of()));
        var interceptor=new OpenApiKeyInterceptor(keys,policy,new ObjectMapper());
        var request=new MockHttpServletRequest("GET","/api/v1/usage");
        String id=java.util.UUID.randomUUID().toString();
        request.setAttribute(com.ylcloud.config.GatewayApiKeyIdentityFilter.IDENTITY,new com.ylcloud.security.ApiKeyIdentityCodec.Identity(3,7,id));
        var response=new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request,response,new Object()));
        assertEquals(id,com.ylcloud.service.metering.ApiUsageContext.current().requestId);
        org.mockito.Mockito.verify(keys,org.mockito.Mockito.never()).authenticate(org.mockito.ArgumentMatchers.any());
        interceptor.afterCompletion(request,response,new Object(),null);
        org.junit.jupiter.api.Assertions.assertNull(com.ylcloud.service.metering.ApiUsageContext.current());
    }
    @Test
    void authenticatesApiKeyAndMarksUnversionedAlias() throws Exception {
        UserApiKeyService keys = mock(UserApiKeyService.class);
        OpenApiVersionPolicyService policy = mock(OpenApiVersionPolicyService.class);
        when(keys.authenticate("ylk_test")).thenReturn(new ApiKeyPrincipal(3L,7L,"prefix","READ",1L,
                Set.of("DRIVE_READ"),Set.of()));
        OpenApiKeyInterceptor interceptor = new OpenApiKeyInterceptor(keys,policy,new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET","/api/open/files");
        request.addHeader("Authorization","Bearer ylk_test");
        request.addHeader("X-Trace-Id","client_trace_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request,response,new Object()));
        assertEquals("v1",response.getHeader("X-API-Version"));
        assertTrue(response.getHeader("Warning").contains("pin /api/v1"));
        assertEquals("client_trace_123",response.getHeader("X-Trace-Id"));
        interceptor.afterCompletion(request,response,new Object(),null);
        verify(keys).authenticate("ylk_test");
    }

    @Test
    void writeOnReadOnlyVersionUsesStableErrorEnvelope() throws Exception {
        UserApiKeyService keys = mock(UserApiKeyService.class);
        OpenApiVersionPolicyService policy = mock(OpenApiVersionPolicyService.class);
        when(keys.authenticate("key")).thenReturn(new ApiKeyPrincipal(3L,7L,"prefix","WRITE",1L,Set.of(),Set.of()));
        doThrow(new BaseException(410,"read only")).when(policy).requireWriteAllowed("v1");
        OpenApiKeyInterceptor interceptor = new OpenApiKeyInterceptor(keys,policy,new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST","/api/v1/files/folders");
        request.addHeader("Authorization","Bearer key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request,response,new Object()));
        assertEquals(410,response.getStatus());
        assertTrue(response.getContentAsString().contains("API_VERSION_READ_ONLY"));
        assertTrue(response.getContentAsString().contains("traceId"));
    }
}
