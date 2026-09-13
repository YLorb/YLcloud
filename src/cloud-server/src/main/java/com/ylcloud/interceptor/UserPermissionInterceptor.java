package com.ylcloud.interceptor;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.constant.UserPermissionKeys;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class UserPermissionInterceptor implements HandlerInterceptor {
    private final AccessControlService accessControlService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Long userId = BaseContext.getCurrentId();
        if(userId == null) return true;
        try {
            for(String permission : requiredPermissions(request)) accessControlService.require(userId,permission);
            return true;
        } catch(ForbiddenException ex) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"" + ex.getMessage() + "\",\"data\":null}");
            return false;
        }
    }

    Set<String> requiredPermissions(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        Set<String> required = new LinkedHashSet<>();
        if(uri.startsWith("/api/admin/") || uri.equals("/api/user/current") || uri.startsWith("/api/site/")) return required;
        if(uri.startsWith("/api/file/") || uri.startsWith("/api/space/") || uri.startsWith("/api/knowledge/")
                || uri.startsWith("/api/async") || uri.startsWith("/api/storage/")) {
            required.add(UserPermissionKeys.CLOUD_DRIVE);
        }
        if(uri.contains("/download")) required.add(UserPermissionKeys.FILE_DOWNLOAD);
        if(isUploadOrCreate(uri,method)) required.add(UserPermissionKeys.FILE_UPLOAD);
        if(uri.startsWith("/api/knowledge/") || uri.matches("^/api/space/[^/]+/(rag|knowledge)(/.*)?$")) {
            required.add(UserPermissionKeys.KNOWLEDGE_USE);
        }
        if("POST".equals(method) && (uri.matches("^/api/space/[^/]+/files/(upload|import)$")
                || uri.matches("^/api/space/[^/]+/rag/links$"))) {
            required.add(UserPermissionKeys.KNOWLEDGE_USE);
            required.add(UserPermissionKeys.KNOWLEDGE_FILE_ADD);
        }
        return required;
    }

    private boolean isUploadOrCreate(String uri, String method) {
        if(!"POST".equals(method)) return false;
        return uri.equals("/api/file/upload") || uri.startsWith("/api/file/multipart/")
                || uri.matches("^/api/file/[01]$")
                || uri.matches("^/api/space/[^/]+/files/(folder|import|upload)$")
                || uri.matches("^/api/space/[^/]+/files/[^/]+/versions$");
    }
}
