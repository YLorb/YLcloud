package com.ylcloud.workflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 验证 Workflow 回调和 Tool 调用 Token，并支持 active/previous 短重叠轮换。 */
@Component
public class ServiceJwtVerifier {
    private final SecretKey activeKey;
    private final SecretKey previousKey;
    private final long previousValidUntilEpochSeconds;
    private final String expectedIssuer;
    private final String expectedSubject;
    private final long maxTtlSeconds;
    private final long clockSkewSeconds;
    private final Clock clock;

    @Autowired
    public ServiceJwtVerifier(
            @Value("${ylcloud.service-jwt.active-secret}") String activeSecret,
            @Value("${ylcloud.service-jwt.previous-secret:}") String previousSecret,
            @Value("${ylcloud.service-jwt.previous-valid-until-epoch-seconds:0}") long previousValidUntilEpochSeconds,
            @Value("${ylcloud.service-jwt.workflow-issuer:ylcloud-workflow}") String expectedIssuer,
            @Value("${ylcloud.service-jwt.workflow-subject:ylcloud-workflow}") String expectedSubject,
            @Value("${ylcloud.service-jwt.max-ttl-seconds:300}") long maxTtlSeconds,
            @Value("${ylcloud.service-jwt.clock-skew-seconds:30}") long clockSkewSeconds
    ) {
        this(activeSecret, previousSecret, previousValidUntilEpochSeconds, expectedIssuer, expectedSubject,
                maxTtlSeconds, clockSkewSeconds, Clock.systemUTC());
    }

    ServiceJwtVerifier(
            String activeSecret,
            String previousSecret,
            long previousValidUntilEpochSeconds,
            String expectedIssuer,
            String expectedSubject,
            long maxTtlSeconds,
            long clockSkewSeconds,
            Clock clock
    ) {
        this.activeKey = ServiceJwtIssuer.key(activeSecret, "active");
        this.previousKey = previousSecret == null || previousSecret.isBlank()
                ? null : ServiceJwtIssuer.key(previousSecret, "previous");
        if (this.previousKey != null && previousValidUntilEpochSeconds <= 0) {
            throw new IllegalArgumentException("previous service JWT secret requires a validity deadline");
        }
        this.previousValidUntilEpochSeconds = previousValidUntilEpochSeconds;
        if (maxTtlSeconds < 1 || maxTtlSeconds > 300 || clockSkewSeconds < 0 || clockSkewSeconds > 60) {
            throw new IllegalArgumentException("invalid service JWT TTL or clock skew");
        }
        if (this.previousKey != null && previousValidUntilEpochSeconds
                > clock.instant().getEpochSecond() + maxTtlSeconds + clockSkewSeconds) {
            throw new IllegalArgumentException("previous service JWT overlap exceeds permitted window");
        }
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer);
        this.expectedSubject = Objects.requireNonNull(expectedSubject);
        this.maxTtlSeconds = maxTtlSeconds;
        this.clockSkewSeconds = clockSkewSeconds;
        this.clock = clock;
    }

    public ServiceJwtIdentity verify(
            String token,
            String expectedAudience,
            Set<String> requiredScopes,
            ServiceJwtBinding expectedBinding
    ) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw invalidToken();
        }
        Jws<Claims> parsed = parseWithRotation(token);
        Claims claims = parsed.getBody();
        if (!"HS256".equals(parsed.getHeader().getAlgorithm())
                || !expectedSubject.equals(claims.getSubject())
                || !expectedAudience.equals(claims.getAudience())
                || claims.getIssuedAt() == null || claims.getNotBefore() == null
                || claims.getExpiration() == null
                || claims.getId() == null || claims.getId().isBlank()) {
            throw invalidToken();
        }
        long lifetimeMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        if (lifetimeMillis <= 0 || lifetimeMillis > maxTtlSeconds * 1000L
                || claims.getIssuedAt().getTime() > clock.millis() + clockSkewSeconds * 1000L) {
            throw invalidToken();
        }
        Set<String> scopes = scopes(claims.get("scope"));
        if (!scopes.containsAll(requiredScopes)) {
            throw new ServiceJwtException(
                    "INSUFFICIENT_SERVICE_SCOPE",
                    "service token does not grant the required scope"
            );
        }
        ServiceJwtBinding actualBinding = binding(claims);
        requireBindings(actualBinding, expectedBinding);
        return new ServiceJwtIdentity(
                claims.getIssuer(), claims.getSubject(), claims.getAudience(),
                Set.copyOf(scopes), actualBinding, claims.getId()
        );
    }

    private Jws<Claims> parseWithRotation(String token) {
        try {
            return parse(token, activeKey);
        } catch (JwtException | IllegalArgumentException activeFailure) {
            if (previousKey != null
                    && clock.instant().getEpochSecond() <= previousValidUntilEpochSeconds) {
                try {
                    return parse(token, previousKey);
                } catch (JwtException | IllegalArgumentException ignored) {
                    // 对外只暴露固定错误，避免泄露当前使用了哪把密钥或验签失败细节。
                }
            }
            throw invalidToken();
        }
    }

    private Jws<Claims> parse(String token, SecretKey key) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(expectedIssuer)
                .setAllowedClockSkewSeconds(clockSkewSeconds)
                .setClock(() -> java.util.Date.from(clock.instant()))
                .build()
                .parseClaimsJws(token);
    }

    private Set<String> scopes(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof String text) {
            for (String scope : text.split("\\s+")) {
                if (!scope.isBlank()) result.add(scope);
            }
        } else if (value instanceof Collection<?> values) {
            for (Object scope : values) {
                if (!(scope instanceof String text) || text.isBlank()) throw invalidToken();
                result.add(text);
            }
        } else {
            throw invalidToken();
        }
        return result;
    }

    private ServiceJwtBinding binding(Claims claims) {
        return new ServiceJwtBinding(
                number(claims, "userId"), number(claims, "apiKeyId"), number(claims, "sessionId"), number(claims, "messageId"),
                text(claims, "runId"), text(claims, "executionId"),
                text(claims, "nodeId"), text(claims, "invocationId")
        );
    }

    private Long number(Claims claims, String name) {
        Object value = claims.get(name);
        if (value == null) return null;
        if (!(value instanceof Number number) || number.longValue() < 1) throw invalidToken();
        return number.longValue();
    }

    private String text(Claims claims, String name) {
        Object value = claims.get(name);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) throw invalidToken();
        return text;
    }

    private void requireBindings(ServiceJwtBinding actual, ServiceJwtBinding expected) {
        if (expected == null) return;
        requireEqual(actual.userId(), expected.userId());
        requireEqual(actual.apiKeyId(), expected.apiKeyId());
        requireEqual(actual.sessionId(), expected.sessionId());
        requireEqual(actual.messageId(), expected.messageId());
        requireEqual(actual.runId(), expected.runId());
        requireEqual(actual.executionId(), expected.executionId());
        requireEqual(actual.nodeId(), expected.nodeId());
        requireEqual(actual.invocationId(), expected.invocationId());
    }

    private void requireEqual(Object actual, Object expected) {
        if (expected != null && !expected.equals(actual)) {
            throw new ServiceJwtException(
                    "SERVICE_TOKEN_BINDING_MISMATCH",
                    "service token is not bound to this resource"
            );
        }
    }

    private ServiceJwtException invalidToken() {
        return new ServiceJwtException("INVALID_SERVICE_TOKEN", "invalid service token");
    }
}
