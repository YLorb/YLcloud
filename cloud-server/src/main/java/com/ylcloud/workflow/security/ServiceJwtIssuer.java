package com.ylcloud.workflow.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 使用 active secret 签发最长五分钟的服务 Token。 */
@Component
public class ServiceJwtIssuer {
    private static final Set<String> ALLOWED_AUDIENCES = Set.of(
            ServiceJwtAudience.WORKFLOW,
            ServiceJwtAudience.TOOL_GATEWAY,
            ServiceJwtAudience.MODEL_SERVICE,
            ServiceJwtAudience.WORKFLOW_CALLBACK
    );
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_.:-]{1,127}$");
    private final SecretKey activeKey;
    private final String issuer;
    private final String subject;
    private final long maxTtlSeconds;
    private final Clock clock;

    @Autowired
    public ServiceJwtIssuer(
            @Value("${ylcloud.service-jwt.active-secret}") String activeSecret,
            @Value("${ylcloud.service-jwt.issuer:ylcloud-app}") String issuer,
            @Value("${ylcloud.service-jwt.subject:ylcloud-app}") String subject,
            @Value("${ylcloud.service-jwt.max-ttl-seconds:300}") long maxTtlSeconds
    ) {
        this(activeSecret, issuer, subject, maxTtlSeconds, Clock.systemUTC());
    }

    ServiceJwtIssuer(String activeSecret, String issuer, String subject, long maxTtlSeconds, Clock clock) {
        this.activeKey = key(activeSecret, "active");
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("service JWT issuer and subject are required");
        }
        if (maxTtlSeconds < 1 || maxTtlSeconds > 300) {
            throw new IllegalArgumentException("service JWT max TTL must be between 1 and 300 seconds");
        }
        this.issuer = issuer;
        this.subject = subject;
        this.maxTtlSeconds = maxTtlSeconds;
        this.clock = clock;
    }

    public String issue(
            String audience,
            Set<String> scopes,
            ServiceJwtBinding binding,
            long ttlSeconds
    ) {
        if (!ALLOWED_AUDIENCES.contains(audience) || scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("service JWT audience and scopes are required");
        }
        if (ttlSeconds < 1 || ttlSeconds > maxTtlSeconds) {
            throw new IllegalArgumentException("service JWT TTL exceeds configured maximum");
        }
        Set<String> normalizedScopes = new LinkedHashSet<>(scopes);
        if (normalizedScopes.stream().anyMatch(
                scope -> scope == null || !SCOPE_PATTERN.matcher(scope).matches())) {
            throw new IllegalArgumentException("service JWT scope is invalid");
        }
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(issuer)
                .setSubject(subject)
                .setAudience(audience)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setNotBefore(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("scope", normalizedScopes);
        putBindings(builder, binding);
        return builder.signWith(activeKey, SignatureAlgorithm.HS256).compact();
    }

    private void putBindings(io.jsonwebtoken.JwtBuilder builder, ServiceJwtBinding binding) {
        if (binding == null) {
            return;
        }
        put(builder, "userId", binding.userId());
        put(builder, "apiKeyId", binding.apiKeyId());
        put(builder, "sessionId", binding.sessionId());
        put(builder, "messageId", binding.messageId());
        put(builder, "runId", binding.runId());
        put(builder, "executionId", binding.executionId());
        put(builder, "nodeId", binding.nodeId());
        put(builder, "invocationId", binding.invocationId());
    }

    private void put(io.jsonwebtoken.JwtBuilder builder, String name, Object value) {
        if (value != null) {
            if (value instanceof Number number && number.longValue() < 1) {
                throw new IllegalArgumentException("service JWT numeric binding must be positive");
            }
            if (value instanceof String text && (text.isBlank() || text.length() > 128)) {
                throw new IllegalArgumentException("service JWT string binding must be 1-128 characters");
            }
            builder.claim(name, value);
        }
    }

    static SecretKey key(String secret, String name) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(name + " service JWT secret must be at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
