package com.ylcloud.gateway;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reads the existing authoritative Session store without blocking the Netty event loop. */
@Component
public class GatewaySessionStore {
    private final DatabaseClient database;
    public GatewaySessionStore(DatabaseClient database) { this.database = database; }
    public Mono<GatewayIdentityCodec.Identity> authenticate(String credential) {
        String hash = GatewayIdentityCodec.hash(credential);
        return database.sql("""
            SELECT u.user_id,u.username,u.nickname,u.role,u.deployment_owner
            FROM user_login_session s JOIN users u ON u.user_id=s.user_id
            WHERE s.session_id_hash=:hash AND s.revoked_at IS NULL
              AND s.session_version=u.session_version AND u.status=1
              AND (u.account_status IS NULL OR u.account_status='ACTIVE')
              AND s.expires_at>UTC_TIMESTAMP(6)
              AND s.last_seen_at>DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 DAY)
            """).bind("hash", hash).map((row, metadata) -> new GatewayIdentityCodec.Identity(
                    row.get("user_id", Long.class), row.get("username", String.class), row.get("nickname", String.class),
                    row.get("role", String.class), booleanValue(row.get("deployment_owner"))))
                .one().flatMap(identity -> database.sql("""
                    UPDATE user_login_session SET last_seen_at=UTC_TIMESTAMP(6)
                    WHERE session_id_hash=:hash AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(6)
                      AND last_seen_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE)
                      AND last_seen_at>DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 DAY)
                    """).bind("hash", hash).fetch().rowsUpdated().thenReturn(identity));
    }
    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }
}
