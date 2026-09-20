package com.ylcloud.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserSessionServiceTest {
    @Test void persistentAndBrowserCookiesHaveDifferentRetention() {
        var service = new BrowserSessionService(mock(JdbcTemplate.class), true);
        var persistent = new MockHttpServletResponse();
        service.setCookie(persistent, "a".repeat(43), true);
        String header = persistent.getHeader("Set-Cookie");
        assertTrue(header.contains("__Host-ylcloud_session="));
        assertTrue(header.contains("Max-Age=1296000"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/"));
        assertFalse(header.contains("Domain="));
        var browser = new MockHttpServletResponse();
        service.setCookie(browser, "b".repeat(43), false);
        assertFalse(browser.getHeader("Set-Cookie").contains("Max-Age"));
        service.clearCookie(browser);
        assertTrue(browser.getHeaders("Set-Cookie").stream().anyMatch(v -> v.contains("Max-Age=0")));
    }
    @Test void credentialRejectsDuplicateMalformedAndLegacyTokens() {
        var service = new BrowserSessionService(mock(JdbcTemplate.class), false);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer legacy-jwt");
        assertNull(service.credential(request));
        request.setCookies(new Cookie(service.cookieName(), "bad"));
        assertNull(service.credential(request));
        request.setCookies(new Cookie(service.cookieName(), "a".repeat(43)), new Cookie(service.cookieName(), "b".repeat(43)));
        assertNull(service.credential(request));
        request.setCookies(new Cookie(service.cookieName(), "a".repeat(43)));
        assertEquals("a".repeat(43), service.credential(request));
    }
    @Test void creationStoresOnlyHashAndRotatesExistingCookie() {
        var db = mock(JdbcTemplate.class);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        var service = new BrowserSessionService(db, false);
        var request = new MockHttpServletRequest();
        String old = "a".repeat(43);
        request.setCookies(new Cookie(service.cookieName(), old));
        String raw = service.create(7L, request);
        assertEquals(43, raw.length());
        assertNotEquals(old, raw);
        var hash = ArgumentCaptor.forClass(Object.class);
        verify(db).update(startsWith("insert into user_login_session"), hash.capture(), eq(7L));
        assertEquals(BrowserSessionService.hash(raw), hash.getValue());
        assertNotEquals(raw, hash.getValue());
        verify(db).update(startsWith("update user_login_session set revoked_at"), eq(BrowserSessionService.hash(old)));
    }
}
