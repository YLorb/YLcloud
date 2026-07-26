package com.ylcloud.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.OpenApiErrorEnvelope;
import com.ylcloud.VO.OpenApiMeta;
import com.ylcloud.context.BaseContext;
import com.ylcloud.context.OpenApiContext;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.OpenApiVersionPolicyService;
import com.ylcloud.service.UserApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OpenApiKeyInterceptor implements HandlerInterceptor {
    public static final String TRACE_ATTRIBUTE = "openApiTraceId";
    public static final String VERSION_ATTRIBUTE = "openApiVersion";
    public static final String UNVERSIONED_ATTRIBUTE = "openApiUnversioned";
    private final UserApiKeyService apiKeyService;
    private final OpenApiVersionPolicyService versionPolicy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
        String traceId = traceId(request.getHeader("X-Trace-Id"));
        boolean unversioned = request.getRequestURI().startsWith("/api/open/") || "/api/open".equals(request.getRequestURI());
        request.setAttribute(TRACE_ATTRIBUTE,traceId);
        request.setAttribute(VERSION_ATTRIBUTE,"v1");
        request.setAttribute(UNVERSIONED_ATTRIBUTE,unversioned);
        response.setHeader("X-Trace-Id",traceId);
        response.setHeader("X-API-Version","v1");
        response.setHeader("X-API-Latest-Version",OpenApiVersionPolicyService.LATEST);
        if(unversioned) response.setHeader("Warning","299 ylcloud \"Unversioned API alias; pin /api/v1 for production\"");
        try {
            String authorization = request.getHeader("Authorization");
            String plaintext = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring(7).trim() : null;
            ApiKeyPrincipal principal = apiKeyService.authenticate(plaintext);
            OpenApiContext.set(principal);
            BaseContext.setCurrentId(principal.userId());
            if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) versionPolicy.requireWriteAllowed("v1");
            return true;
        } catch(BaseException exception) {
            int status = exception.getStatusCode();
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            OpenApiErrorEnvelope body = new OpenApiErrorEnvelope(
                    new OpenApiErrorEnvelope.OpenApiError(code(status),safe(exception.getMessage()),Map.of()),
                    new OpenApiMeta(traceId,"v1","v1",unversioned));
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception ex) {
        OpenApiContext.clear();
        BaseContext.removeCurrentId();
    }

    private String traceId(String candidate) {
        if(candidate != null && candidate.matches("[A-Za-z0-9_-]{8,64}")) return candidate;
        return UUID.randomUUID().toString();
    }

    private String code(int status) {
        return switch(status) {
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 410 -> "API_VERSION_READ_ONLY";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "INVALID_REQUEST";
        };
    }

    private String safe(String value) { return value == null || value.isBlank() ? "请求被拒绝" : value; }
}
