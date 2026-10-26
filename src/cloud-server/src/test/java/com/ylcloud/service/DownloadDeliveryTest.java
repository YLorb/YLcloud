package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadDeliveryTest {
    private final SiteSettingService settings = mock(SiteSettingService.class);
    private boolean streamClosed;
    private final MinioclientUtil storage = new MinioclientUtil("http://127.0.0.1:19000", "fixture-user", "fixture-password", "test-files") {
        @Override public InputStream getObjectStream(String object, String version) {
            return new ByteArrayInputStream(new byte[]{1,2,3,4}) {
                @Override public void close() { streamClosed = true; }
            };
        }
    };
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    @Test void defaultProxyStreamsAndClosesObject() throws Exception {
        storage.configureDownloads(settings, "", "fixture-user", "fixture-password", "us-east-1");
        var response = new MockHttpServletResponse();
        storage.getObject("object", "version", "文件.txt", response);
        assertArrayEquals(new byte[]{1,2,3,4}, response.getContentAsByteArray());
        assertTrue(streamClosed); assertNull(response.getHeader("Location"));
    }
    @Test void directModeSignsPublicHostVersionAndBoundedExpiry() throws Exception {
        when(settings.getString("storage.downloadMode", "proxy")).thenReturn("direct");
        storage.configureDownloads(settings, "https://objects.example.test", "fixture-user", "fixture-password", "us-east-1");
        var response = new MockHttpServletResponse();
        assertTrue(storage.redirectDownload("folder/object", "immutable-v1", "报告.txt", 42, response));
        assertEquals(303, response.getStatus());
        URI location = URI.create(response.getHeader("Location"));
        assertEquals("objects.example.test", location.getHost());
        assertEquals("/test-files/folder/object", location.getPath());
        assertTrue(location.getQuery().contains("versionId=immutable-v1"));
        assertTrue(location.getQuery().contains("X-Amz-Expires=42"));
        assertTrue(location.getQuery().contains("X-Amz-Signature="));
        assertFalse(streamClosed);
    }
    @Test void ajaxDownloadReturnsNavigationUrlWithoutCorsFetch() throws Exception {
        when(settings.getString("storage.downloadMode", "proxy")).thenReturn("direct");
        storage.configureDownloads(settings, "https://objects.example.test", "fixture-user", "fixture-password", "us-east-1");
        var request = new MockHttpServletRequest(); request.addHeader("X-YLCloud-Download", "1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var response = new MockHttpServletResponse();
        storage.getObject("object", "v1", "file.txt", response);
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"downloadUrl\":\"https://objects.example.test/"));
        assertNull(response.getHeader("Location"));
    }
    @Test void missingPublicEndpointDoesNotLeakInternalUrl() throws Exception {
        when(settings.getString("storage.downloadMode", "proxy")).thenReturn("direct");
        storage.configureDownloads(settings, "", "fixture-user", "fixture-password", "us-east-1");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class, () -> storage.getObject("object", "v1", "file", response));
        assertNull(response.getHeader("Location"));
    }
}
