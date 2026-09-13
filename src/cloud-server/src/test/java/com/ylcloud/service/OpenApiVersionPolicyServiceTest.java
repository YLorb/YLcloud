package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiVersionPolicyServiceTest {
    @Test
    void versionBecomesReadOnlyAtConfiguredInstantAndInvalidPolicyFailsClosed() {
        SiteSettingService settings = mock(SiteSettingService.class);
        OpenApiVersionPolicyService service = new OpenApiVersionPolicyService(settings);
        when(settings.getString("api.v1.readOnlyAfter","")).thenReturn(Instant.now().plusSeconds(60).toString());
        service.requireWriteAllowed("v1");
        when(settings.getString("api.v1.readOnlyAfter","")).thenReturn(Instant.now().minusSeconds(60).toString());
        assertEquals(410,assertThrows(BaseException.class,() -> service.requireWriteAllowed("v1")).getStatusCode());
        when(settings.getString("api.v1.readOnlyAfter","")).thenReturn("not-a-time");
        assertEquals(503,assertThrows(BaseException.class,() -> service.requireWriteAllowed("v1")).getStatusCode());
    }

    @Test
    void pageSizeUsesSiteMaximum() {
        SiteSettingService settings = mock(SiteSettingService.class);
        when(settings.getLong("api.maxPageSize",100L)).thenReturn(25L);
        OpenApiVersionPolicyService service = new OpenApiVersionPolicyService(settings);
        assertEquals(25,service.pageSize(null));
        assertEquals(10,service.pageSize(10));
        assertThrows(BaseException.class,() -> service.pageSize(26));
    }
}
