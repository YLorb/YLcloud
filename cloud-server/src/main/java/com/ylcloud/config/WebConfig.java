package com.ylcloud.config;

import com.ylcloud.interceptor.JwtTokenInterceptor;
import com.ylcloud.interceptor.MaintenanceInterceptor;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import com.ylcloud.interceptor.UserPermissionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration //声明这是一个配置类
//WebMvcConfigurer：Spring MVC配置接口，可以自定义MVC相关配置
public class WebConfig implements WebMvcConfigurer {
    @Autowired //自动注入
    private JwtTokenInterceptor jwtTokenInterceptor;
    @Autowired
    private UserPermissionInterceptor userPermissionInterceptor;
    @Autowired
    private OpenApiKeyInterceptor openApiKeyInterceptor;
    @Autowired
    private MaintenanceInterceptor maintenanceInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 维护模式拦截器：需在所有授权之前执行，覆盖所有 /api/** 路径
        // 维护模式下仅放行 GET（只读）、健康检查和维护管理端点；
        // /api/v1/** 和 /api/open/** 不再豁免——Open API 写入在维护期同样应被阻止。
        // /internal/** 路径由 Internal API 自行通过 MaintenanceModeService 检查。
        registry.addInterceptor(maintenanceInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/sign", "/api/login", "/api/site/public-settings",
                        "/api/share/**", "/api/admin/maintenance/disable");

        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/sign")
                .excludePathPatterns("/api/login")
                .excludePathPatterns("/api/site/public-settings")
                .excludePathPatterns("/api/share/**")
                .excludePathPatterns("/api/v1/**", "/api/open/**")
                // Internal API 使用独立 audience/scope/binding Service JWT，由各内部 Controller 强制验签。
                .excludePathPatterns("/internal/**");
        registry.addInterceptor(userPermissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/sign", "/api/login", "/api/site/public-settings", "/api/share/**",
                        "/api/v1/**", "/api/open/**");
        registry.addInterceptor(openApiKeyInterceptor)
                .addPathPatterns("/api/v1/**", "/api/open/**");
    }
}
