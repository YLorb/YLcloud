package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.constant.UserPermissionKeys;
import com.ylcloud.mapper.AccessControlMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessControlServiceTest {
    private final AccessControlMapper mapper = mock(AccessControlMapper.class);
    private final AccessControlService service = new AccessControlService(mapper,new ObjectMapper());

    @Test
    void cloudDriveMasterSwitchOverridesSinglePermissions() {
        when(mapper.resolvePermission(7L,UserPermissionKeys.CLOUD_DRIVE)).thenReturn(0);
        when(mapper.resolvePermission(7L,UserPermissionKeys.FILE_DOWNLOAD)).thenReturn(1);

        assertThrows(ForbiddenException.class,() -> service.require(7L,UserPermissionKeys.FILE_DOWNLOAD));
        Map<String, Boolean> effective = service.effectivePermissions(7L);
        assertFalse(effective.get(UserPermissionKeys.FILE_DOWNLOAD));
    }

    @Test
    void allowsEnabledSinglePermissionWhenMasterSwitchIsEnabled() {
        when(mapper.resolvePermission(8L,UserPermissionKeys.CLOUD_DRIVE)).thenReturn(1);
        when(mapper.resolvePermission(8L,UserPermissionKeys.FILE_UPLOAD)).thenReturn(1);

        service.require(8L,UserPermissionKeys.FILE_UPLOAD);
        assertTrue(service.effectivePermissions(8L).get(UserPermissionKeys.CLOUD_DRIVE));
    }
}
