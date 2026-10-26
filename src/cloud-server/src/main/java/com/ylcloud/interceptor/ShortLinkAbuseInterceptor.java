package com.ylcloud.interceptor;

import com.ylcloud.service.ShortLinkAbuseGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ShortLinkAbuseInterceptor implements HandlerInterceptor {
    private final ShortLinkAbuseGuard guard;
    public ShortLinkAbuseInterceptor(ShortLinkAbuseGuard guard) { this.guard = guard; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        long remaining = guard.check(request.getRemoteAddr());
        if (remaining == 0) return true;
        response.setStatus(502);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(remaining));
        response.getWriter().write("{\"code\":502,\"message\":\"短链接访问已临时限制\",\"data\":null}");
        return false;
    }
}
