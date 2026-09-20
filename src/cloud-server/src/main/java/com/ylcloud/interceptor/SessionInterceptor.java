package com.ylcloud.interceptor;

import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.service.BrowserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class SessionInterceptor implements HandlerInterceptor {
    private final BrowserSessionService sessions;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        BaseContext.removeCurrentId();
        response.setHeader("Cache-Control", "no-store");
        User user;
        try { user = sessions.authenticate(request); }
        catch (DataAccessException ex) {
            reject(response, 503, "登录状态服务暂不可用，请稍后重试");
            return false;
        }
        if (user == null) {
            sessions.clearCookie(response);
            reject(response, 401, "登录已失效，请重新登录");
            return false;
        }
        BaseContext.setCurrentId(user.getId());
        request.setAttribute("userId", user.getId());
        request.setAttribute("username", user.getUsername());
        request.setAttribute(BrowserSessionService.USER_ATTRIBUTE, user);
        return true;
    }
    private void reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
