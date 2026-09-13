package com.ylcloud.interceptor;

import com.ylcloud.constant.UserPermissionKeys;
import com.ylcloud.service.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPermissionInterceptorTest {
    private final UserPermissionInterceptor interceptor = new UserPermissionInterceptor(mock(AccessControlService.class));

    @Test
    void knowledgeUploadRequiresMasterUploadKnowledgeAndAddPermissions() {
        Set<String> required = interceptor.requiredPermissions(request("POST","/api/space/12/files/upload"));

        assertEquals(Set.of(UserPermissionKeys.CLOUD_DRIVE,UserPermissionKeys.FILE_UPLOAD,UserPermissionKeys.KNOWLEDGE_USE,UserPermissionKeys.KNOWLEDGE_FILE_ADD),required);
    }

    @Test
    void ragQueryRequiresKnowledgeUsePermission() {
        Set<String> required = interceptor.requiredPermissions(request("POST","/api/space/12/rag/query"));

        assertTrue(required.contains(UserPermissionKeys.CLOUD_DRIVE));
        assertTrue(required.contains(UserPermissionKeys.KNOWLEDGE_USE));
    }

    @Test
    void adminEndpointsAreNotSubjectToBusinessPermissionSwitches() {
        assertTrue(interceptor.requiredPermissions(request("POST","/api/admin/users")).isEmpty());
    }

    private HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
