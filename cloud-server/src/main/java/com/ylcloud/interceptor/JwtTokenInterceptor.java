package com.ylcloud.interceptor;

import com.ylcloud.context.BaseContext;
import com.ylcloud.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.HandlerInterceptor;

@Component // 告诉spring在应用启动是需要在application context中创建这个类的一个实例。用于标识与自动注册组件类
@RequiredArgsConstructor // @RequiredArgsConstructor:自动生成包含必须参数的构造函数
// implements：表示对接口的实现 entends: 表示对父类的继承
@RequestMapping("/api")
@Slf4j
public class JwtTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 在控制器方法执行前进行拦截
     * true 表示放行，false 表示拦截
     * @param request current HTTP request
     * @param response current HTTP response
     * @param handler chosen handler to execute, for type and/or instance evaluation
     * @return
     * @throws Exception
     */
    @Override // 拦截器
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler) throws Exception {
        String requestURL = request.getRequestURI().toString();
        log.info("拦截到请求：{}",requestURL);

        if(requestURL.contains("/login")) {
            log.info("登录请求，直接放行");
            return true;
        }

        String token = request.getHeader("Authorization");
        log.info("获取令牌：{}",token);
        if(token == null || token.isEmpty()) {
            log.warn("令牌为空，用户未登录，返回401(未授权)");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("请先登录");
            return false;
        }

        /*
            校验令牌
         */
        try {
            /*
                - 为什么要去掉"Bearer "前缀？
                  这是标准的JWT使用规范，"Bearer "表示这是一个持有者令牌。
             */
            if(token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            Claims claims = jwtUtil.parseToken(token);
            log.info("令牌解析成功，用户信息：{}",claims);

            // 将用户信息存入请求属性，供 controller 使用
            /*
            request.setAttribute()：将数据存储在请求属性中
                这些数据在当前请求的整个生命周期内都可用。
                控制器可以通过@RequestAttribute注解获取这些值
             */
            Long userId = claims.get("userId",Long.class);
            request.setAttribute("userId",userId);
            BaseContext.setCurrentId(userId);
            request.setAttribute("username",claims.getSubject());
            log.info("parsed username={}, userId={}", claims.getSubject(), userId);
            log.info("用户信息已存入请求属性，用户名:{}，用户id:{}",claims.getSubject(),userId);
        } catch (Exception e) {
            log.warn("令牌解析失败：{}",e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("令牌无效或已过期");
            return false;
        }
        log.info("令牌信息校验通过，放行");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
