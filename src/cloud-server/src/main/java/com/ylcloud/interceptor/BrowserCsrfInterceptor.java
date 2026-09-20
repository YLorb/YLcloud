package com.ylcloud.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

/** Same-origin AJAX contract: do not enable credentialed cross-origin CORS on these routes. */
@Component
public class BrowserCsrfInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return true;
        String site = request.getHeader("Sec-Fetch-Site");
        if (!"1".equals(request.getHeader("X-YLCloud-Request")) ||
                (site != null && !Set.of("same-origin", "none").contains(site))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"请求来源验证失败，请刷新页面重试\",\"data\":null}");
            return false;
        }
        return true;
    }
}
