package com.ylcloud.config;

import com.ylcloud.security.ApiKeyIdentityCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+25)
public class GatewayApiKeyIdentityFilter extends OncePerRequestFilter {
    public static final String IDENTITY=GatewayApiKeyIdentityFilter.class.getName()+".identity";
    private final boolean required;
    private final ApiKeyIdentityCodec codec;
    public GatewayApiKeyIdentityFilter(@Value("${YLCLOUD_GATEWAY_AUTH_REQUIRED:false}") boolean required,
            @Value("${YLCLOUD_GATEWAY_IDENTITY_SECRET:}") String secret) {
        this.required=required; codec=secret.isBlank() && !required ? null : new ApiKeyIdentityCodec(secret);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(!openApi(request.getRequestURI()) && !openApi(request.getServletPath())) { chain.doFilter(request,response); return; }
        var values=Collections.list(request.getHeaders(ApiKeyIdentityCodec.HEADER));
        if(values.isEmpty() && !required) { chain.doFilter(request,response); return; }
        try {
            if(values.size()!=1 || codec==null) throw new IllegalArgumentException();
            String target=request.getRequestURI()+(request.getQueryString()==null ? "" : "?"+request.getQueryString());
            request.setAttribute(IDENTITY,codec.verify(values.get(0),request.getHeader("X-Request-Id"),request.getMethod(),target));
        } catch(IllegalArgumentException invalid) {
            response.setStatus(401); response.setHeader("Cache-Control","no-store"); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"网关 API Key 身份验证失败\",\"details\":{}}}"); return;
        }
        chain.doFilter(request,response);
    }
    private static boolean openApi(String path) {
        return path!=null && (path.equals("/api/v1") || path.startsWith("/api/v1/") || path.equals("/api/open") || path.startsWith("/api/open/"));
    }
}
