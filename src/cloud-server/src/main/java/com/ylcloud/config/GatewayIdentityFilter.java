package com.ylcloud.config;

import com.ylcloud.entity.User;
import com.ylcloud.security.GatewayIdentityCodec;
import com.ylcloud.service.BrowserSessionService;
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

/** Reject direct external API traffic when gateway authentication is required. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GatewayIdentityFilter extends OncePerRequestFilter {
    public static final String RESOLVED = GatewayIdentityFilter.class.getName() + ".resolved";
    private final boolean required;
    private final GatewayIdentityCodec codec;
    private final BrowserSessionService sessions;
    public GatewayIdentityFilter(@Value("${YLCLOUD_GATEWAY_AUTH_REQUIRED:false}") boolean required,
            @Value("${YLCLOUD_GATEWAY_IDENTITY_SECRET:}") String secret, BrowserSessionService sessions) {
        this.required = required; this.sessions = sessions;
        codec = secret.isBlank() && !required ? null : new GatewayIdentityCodec(secret);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // MVC ignores matrix parameters; never let a noncanonical spelling bypass the trust boundary.
        if (required && (path.indexOf(';') >= 0 || path.toLowerCase(java.util.Locale.ROOT).contains("%3b"))) {
            reject(response); return;
        }
        // Tomcat normalizes/decodes the servlet path before routing (e.g. /%61pi/session).
        // Keep the original URI below for signature binding, but cover both forms for enforcement.
        if (!isApiPath(path) && !isApiPath(request.getServletPath())) { chain.doFilter(request, response); return; }
        var values = Collections.list(request.getHeaders(GatewayIdentityCodec.HEADER));
        if (values.isEmpty() && (!required || ("GET".equals(request.getMethod()) && path.equals("/api/site/public-settings")))) {
            chain.doFilter(request, response); return;
        }
        GatewayIdentityCodec.Identity identity;
        try {
            if (codec == null || values.size() != 1) throw new IllegalArgumentException("Missing gateway identity");
            String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            identity = codec.verify(values.get(0), request.getMethod(), target, sessions.credential(request));
        } catch (IllegalArgumentException invalid) {
            reject(response);
            return;
        }
        request.setAttribute(RESOLVED, Boolean.TRUE);
        if (identity.userId() > 0) {
            User user = new User(); user.setId(identity.userId()); user.setUsername(identity.username());
            user.setNickname(identity.nickname()); user.setRole(identity.role()); user.setDeploymentOwner(identity.owner());
            user.setStatus(1); user.setAccountStatus("ACTIVE");
            request.setAttribute(BrowserSessionService.USER_ATTRIBUTE, user);
        }
        chain.doFilter(request, response);
    }
    private static boolean isApiPath(String path) {
        return path != null && (path.equals("/api") || path.startsWith("/api/"));
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":401,\"message\":\"网关身份验证失败\",\"data\":null}");
    }
}
