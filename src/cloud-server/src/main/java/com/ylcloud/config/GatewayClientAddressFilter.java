package com.ylcloud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only the gateway's secret-authenticated address may replace the transport peer. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayClientAddressFilter extends OncePerRequestFilter {
    private final byte[] proof;
    public GatewayClientAddressFilter(@Value("${YLCLOUD_GATEWAY_FORWARDING_SECRET:}") String secret) {
        if (!secret.isEmpty() && secret.length() < 32) throw new IllegalArgumentException("Invalid gateway forwarding secret");
        proof = secret.getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-YLCloud-Gateway-Proof");
        String address = request.getHeader("X-YLCloud-Client-IP");
        if (proof.length >= 32 && supplied != null && supplied.length() <= 512
                && MessageDigest.isEqual(proof, supplied.getBytes(StandardCharsets.UTF_8))
                && address != null && address.length() <= 45 && address.matches("[0-9a-fA-F:.]+")) {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public String getRemoteAddr() { return address; }
                @Override public String getRemoteHost() { return address; }
            }, response);
        } else chain.doFilter(request, response);
    }
}
