package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDeploymentOwnerPermissionTest {
    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void ordinaryAdminCannotEnablePrivateWebhookTargets() {
        LoginMapper users = mock(LoginMapper.class);
        AdminPermissionService service = new AdminPermissionService(users);
        BaseContext.setCurrentId(7L);
        User admin = new User(); admin.setId(7L); admin.setRole("ADMIN"); admin.setDeploymentOwner(false);
        when(users.getById(7L)).thenReturn(admin);

        assertThrows(ForbiddenException.class,service::requireDeploymentOwner);

        admin.setDeploymentOwner(true);
        assertDoesNotThrow(service::requireDeploymentOwner);
    }
}
