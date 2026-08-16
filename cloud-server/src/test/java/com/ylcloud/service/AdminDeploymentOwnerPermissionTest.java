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
import static org.mockito.Mockito.verify;
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

    @Test
    void deploymentOwnerDenialIsAudited() {
        LoginMapper users = mock(LoginMapper.class);
        SecurityAuditService audit = mock(SecurityAuditService.class);
        AdminPermissionService service = new AdminPermissionService(users);
        service.setAuditService(audit);
        BaseContext.setCurrentId(8L);
        User admin = new User(); admin.setId(8L); admin.setRole("ADMIN"); admin.setDeploymentOwner(false);
        when(users.getById(8L)).thenReturn(admin);

        assertThrows(ForbiddenException.class,service::requireDeploymentOwner);

        verify(audit).recordDenied("DEPLOYMENT_OWNER","ACCESS",8L,null,
                "DEPLOYMENT","CURRENT",null,"仅部署所有者可以执行此操作");
    }
}
