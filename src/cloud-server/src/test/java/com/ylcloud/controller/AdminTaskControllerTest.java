package com.ylcloud.controller;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.AdminTaskService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminTaskControllerTest {
    @Test void deniesListAndArchiveBeforeAccessingData() {
        AdminPermissionService permissions = mock(AdminPermissionService.class);
        AdminTaskService tasks = mock(AdminTaskService.class);
        AdminTaskController controller = new AdminTaskController(permissions, tasks);
        doThrow(new ForbiddenException("仅管理员可用")).when(permissions).requireAdmin();
        assertThrows(ForbiddenException.class, () -> controller.page(false, null, null, null, 1, 20));
        assertThrows(ForbiddenException.class, () -> controller.archive(1L));
        verifyNoInteractions(tasks);
    }
}
