package com.ylcloud.controller;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.service.AdminMetricsService;
import com.ylcloud.service.AdminPermissionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminMetricsControllerTest {
    @Test void requiresAdminBeforeReadingCounts() {
        AdminPermissionService permissions = mock(AdminPermissionService.class);
        AdminMetricsService metrics = mock(AdminMetricsService.class);
        doThrow(new ForbiddenException("ADMIN required")).when(permissions).requireAdmin();
        assertThrows(ForbiddenException.class, () -> new AdminMetricsController(permissions, metrics).daily(7));
        verifyNoInteractions(metrics);
    }
}
