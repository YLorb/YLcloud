package com.ylcloud.interceptor;

import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.service.BrowserSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SessionSecurityTest {
    @AfterEach void clear() { BaseContext.removeCurrentId(); }
    @Test void writesRequireCustomHeaderAndSameOriginFetchMetadata() throws Exception {
        var csrf = new BrowserCsrfInterceptor();
        for (String method : new String[]{"POST","PUT","PATCH","DELETE"}) {
            var request = new MockHttpServletRequest(method, "/api/login");
            var response = new MockHttpServletResponse();
            assertFalse(csrf.preHandle(request, response, null));
            assertEquals(403,response.getStatus());
            request.addHeader("X-YLCloud-Request","1");
            request.addHeader("Sec-Fetch-Site","cross-site");
            assertFalse(csrf.preHandle(request,new MockHttpServletResponse(),null));
            request.removeHeader("Sec-Fetch-Site");
            request.addHeader("Sec-Fetch-Site","same-origin");
            assertTrue(csrf.preHandle(request,new MockHttpServletResponse(),null));
        }
        assertTrue(csrf.preHandle(new MockHttpServletRequest("GET","/api/session"),new MockHttpServletResponse(),null));
    }
    @Test void invalidSessionClearsCookieAndNeverUsesBearerFallback() throws Exception {
        var sessions = mock(BrowserSessionService.class);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer old-valid-jwt");
        var response = new MockHttpServletResponse();
        BaseContext.setCurrentId(99L);
        assertFalse(new SessionInterceptor(sessions).preHandle(request,response,null));
        assertEquals(401,response.getStatus());
        assertNull(BaseContext.getCurrentId());
        verify(sessions).clearCookie(response);
    }
    @Test void databaseFailureIs503AndDoesNotClearCookie() throws Exception {
        var sessions = mock(BrowserSessionService.class);
        when(sessions.authenticate(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        var response = new MockHttpServletResponse();
        assertFalse(new SessionInterceptor(sessions).preHandle(new MockHttpServletRequest(),response,null));
        assertEquals(503,response.getStatus());
        verify(sessions,never()).clearCookie(any());
    }
    @Test void successfulRequestSetsAndFinallyClearsIdentity() throws Exception {
        var sessions = mock(BrowserSessionService.class);
        User user = new User(); user.setId(7L); user.setUsername("test");
        when(sessions.authenticate(any())).thenReturn(user);
        var interceptor = new SessionInterceptor(sessions);
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request,response,null));
        assertEquals(7L,BaseContext.getCurrentId());
        assertSame(user,request.getAttribute(BrowserSessionService.USER_ATTRIBUTE));
        interceptor.afterCompletion(request,response,null,null);
        assertNull(BaseContext.getCurrentId());
    }
}
