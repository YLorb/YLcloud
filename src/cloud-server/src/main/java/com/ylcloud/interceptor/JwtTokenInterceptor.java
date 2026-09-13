package com.ylcloud.interceptor;

import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;
    private final LoginMapper loginMapper;

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler) throws Exception {
        BaseContext.removeCurrentId();
        String requestURI = request.getRequestURI();
        log.info("jwt intercept request={}",requestURI);

        String token = request.getHeader("Authorization");
        if(token == null || token.isBlank()) {
            writeUnauthorized(response, "请先登录");
            return false;
        }

        try {
            if(token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId",Long.class);
            if(userId == null) {
                writeUnauthorized(response, "登录状态无效");
                return false;
            }
            User user = loginMapper.getById(userId);
            if(user == null || !StatusConstant.ENABLE.equals(user.getStatus())) {
                writeUnauthorized(response, "用户不存在或已被禁用");
                return false;
            }

            // TASK-010: 检查账号生命周期状态
            String accountStatus = user.getAccountStatus();
            if(accountStatus != null && !"ACTIVE".equals(accountStatus)) {
                writeUnauthorized(response, "账号已注销，请联系管理员恢复");
                return false;
            }

            BaseContext.setCurrentId(userId);
            request.setAttribute("userId",userId);
            request.setAttribute("username",claims.getSubject());
            log.info("jwt authorized username={}, userId={}", claims.getSubject(), userId);
            return true;
        } catch (Exception e) {
            log.warn("jwt parse failed: {}",e.getMessage());
            writeUnauthorized(response, "登录已过期，请重新登录");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
