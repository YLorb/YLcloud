package com.ylcloud.interceptor;

import com.ylcloud.service.MaintenanceModeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TASK-014: 维护模式拦截器。
 * 在维护模式下拒绝写入操作和新任务提交，仅放行只读和健康端点。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceInterceptor implements HandlerInterceptor {
    private final MaintenanceModeService maintenanceService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (!maintenanceService.isRequestAllowed(method, path)) {
            log.warn("Maintenance mode blocked request: {} {}", method, path);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"message\":\"系统维护中，仅允许只读操作\",\"data\":null}");
            return false;
        }

        return true;
    }
}
