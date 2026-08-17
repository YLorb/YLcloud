package com.ylcloud.workflow.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceJwtTest {
    private static final String ACTIVE = "active-service-secret-32-bytes-minimum-0001";
    private static final String PREVIOUS = "previous-service-secret-32-bytes-minimum-01";
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void issuedTokenDeclaresJwtTypeForCrossLanguageVerifiers() {
        ServiceJwtIssuer issuer = new ServiceJwtIssuer(
                ACTIVE, "ylcloud-app", "ylcloud-app", 300, CLOCK
        );

        String token = issuer.issue(
                ServiceJwtAudience.MODEL_SERVICE,
                Set.of("model.embed"),
                ServiceJwtBinding.run("cross-language-contract"),
                120
        );
        String header = new String(
                Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8
        );

        assertTrue(header.contains("\"alg\":\"HS256\""));
        assertTrue(header.contains("\"typ\":\"JWT\""));
    }

    @Test
    void issuesShortBoundTokenAndRejectsWrongAudienceScopeAndBinding() {
        ServiceJwtIssuer issuer = new ServiceJwtIssuer(
                ACTIVE, "ylcloud-workflow", "ylcloud-workflow", 300, CLOCK
        );
        ServiceJwtVerifier verifier = verifier(ACTIVE, "");
        ServiceJwtBinding binding = new ServiceJwtBinding(
                101L, 151L, 201L, 302L, "run-1", "exec-1", "node-1", "invoke-1"
        );
        String token = issuer.issue(
                ServiceJwtAudience.TOOL_GATEWAY,
                Set.of("tool.memory.read", "tool.knowledge.search"),
                binding,
                120
        );

        ServiceJwtIdentity identity = verifier.verify(
                token, ServiceJwtAudience.TOOL_GATEWAY,
                Set.of("tool.memory.read"), binding
        );
        assertEquals("run-1", identity.binding().runId());

        assertCode("INVALID_SERVICE_TOKEN", () -> verifier.verify(
                token, ServiceJwtAudience.WORKFLOW_CALLBACK, Set.of("tool.memory.read"), binding
        ));
        assertCode("INSUFFICIENT_SERVICE_SCOPE", () -> verifier.verify(
                token, ServiceJwtAudience.TOOL_GATEWAY, Set.of("tool.memory.delete"), binding
        ));
        assertCode("SERVICE_TOKEN_BINDING_MISMATCH", () -> verifier.verify(
                token, ServiceJwtAudience.TOOL_GATEWAY, Set.of("tool.memory.read"),
                new ServiceJwtBinding(999L, null, null, null, null, null, null, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue(
                "user-browser", Set.of("model.embed"), binding, 120
        ));
    }

    @Test
    void activePreviousRotationOverlapIsBounded() {
        ServiceJwtIssuer oldIssuer = new ServiceJwtIssuer(
                PREVIOUS, "ylcloud-workflow", "ylcloud-workflow", 300, CLOCK
        );
        String oldToken = oldIssuer.issue(
                ServiceJwtAudience.WORKFLOW_CALLBACK,
                Set.of("workflow.callback.deliver"),
                ServiceJwtBinding.run("run-1"),
                120
        );
        verifier(ACTIVE, PREVIOUS).verify(
                oldToken, ServiceJwtAudience.WORKFLOW_CALLBACK,
                Set.of("workflow.callback.deliver"), ServiceJwtBinding.run("run-1")
        );
        assertCode("INVALID_SERVICE_TOKEN", () -> verifier(ACTIVE, "").verify(
                oldToken, ServiceJwtAudience.WORKFLOW_CALLBACK,
                Set.of("workflow.callback.deliver"), ServiceJwtBinding.run("run-1")
        ));
        ServiceJwtVerifier expiredOverlap = new ServiceJwtVerifier(
                ACTIVE, PREVIOUS, NOW.minusSeconds(1).getEpochSecond(),
                "ylcloud-workflow", "ylcloud-workflow", 300, 0, CLOCK
        );
        assertCode("INVALID_SERVICE_TOKEN", () -> expiredOverlap.verify(
                oldToken, ServiceJwtAudience.WORKFLOW_CALLBACK,
                Set.of("workflow.callback.deliver"), ServiceJwtBinding.run("run-1")
        ));
    }

    @Test
    void rejectsExpiredForgedAndOverlongLifetimeTokens() {
        ServiceJwtIssuer expiredIssuer = new ServiceJwtIssuer(
                ACTIVE, "ylcloud-workflow", "ylcloud-workflow", 300,
                Clock.fixed(NOW.minusSeconds(600), ZoneOffset.UTC)
        );
        String expired = expiredIssuer.issue(
                ServiceJwtAudience.MODEL_SERVICE, Set.of("model.embed"), null, 120
        );
        assertCode("INVALID_SERVICE_TOKEN", () -> verifier(ACTIVE, "").verify(
                expired, ServiceJwtAudience.MODEL_SERVICE, Set.of("model.embed"), null
        ));

        String forged = expired.substring(0, expired.length() - 1) + "A";
        assertCode("INVALID_SERVICE_TOKEN", () -> verifier(ACTIVE, "").verify(
                forged, ServiceJwtAudience.MODEL_SERVICE, Set.of("model.embed"), null
        ));

        String tooLong = Jwts.builder()
                .setIssuer("ylcloud-workflow")
                .setSubject("ylcloud-workflow")
                .setAudience(ServiceJwtAudience.MODEL_SERVICE)
                .setId("long-lived")
                .setIssuedAt(Date.from(NOW))
                .setExpiration(Date.from(NOW.plusSeconds(301)))
                .claim("scope", Set.of("model.embed"))
                .signWith(ServiceJwtIssuer.key(ACTIVE, "active"), SignatureAlgorithm.HS256)
                .compact();
        assertCode("INVALID_SERVICE_TOKEN", () -> verifier(ACTIVE, "").verify(
                tooLong, ServiceJwtAudience.MODEL_SERVICE, Set.of("model.embed"), null
        ));
    }

    private ServiceJwtVerifier verifier(String active, String previous) {
        return new ServiceJwtVerifier(
                active, previous, previous.isBlank() ? 0 : NOW.plusSeconds(60).getEpochSecond(),
                "ylcloud-workflow", "ylcloud-workflow", 300, 0, CLOCK
        );
    }

    private void assertCode(String expected, Runnable action) {
        ServiceJwtException exception = assertThrows(ServiceJwtException.class, action::run);
        assertEquals(expected, exception.getCode());
    }
}
