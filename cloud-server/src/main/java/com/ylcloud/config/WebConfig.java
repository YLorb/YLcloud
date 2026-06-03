package com.ylcloud.config;

import com.ylcloud.interceptor.JwtTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration //声明这是一个配置类
//WebMvcConfigurer：Spring MVC配置接口，可以自定义MVC相关配置
public class WebConfig implements WebMvcConfigurer {
    @Autowired //自动注入
    private JwtTokenInterceptor jwtTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/sign")
                .excludePathPatterns("/api/login")
                .excludePathPatterns("/api/share/**");
    }
}
