package com.ylcloud.gateway;

import io.r2dbc.spi.ConnectionFactories;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.r2dbc.core.DatabaseClient;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GATEWAY_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewaySessionStoreIntegrationTest {
    private DatabaseClient db;
    private GatewaySessionStore store;
    private final long id=8_000_000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%1_000_000);
    private final String credential=UUID.randomUUID().toString();
    private String hash;
    private void execute(String sql) { db.sql(sql).fetch().rowsUpdated().block(Duration.ofSeconds(10)); }
    @BeforeAll void prepare() {
        String url=System.getenv("GATEWAY_TEST_DB_URL");
        if(!url.contains("/gateway_session_test")) throw new IllegalArgumentException("Only isolated gateway_session_test schema allowed");
        db=DatabaseClient.create(ConnectionFactories.get(url)); store=new GatewaySessionStore(db);
        execute("CREATE TABLE IF NOT EXISTS users(user_id BIGINT PRIMARY KEY,username VARCHAR(100),nickname VARCHAR(100),role VARCHAR(20),deployment_owner TINYINT,status INT,account_status VARCHAR(20),session_version BIGINT)");
        execute("ALTER TABLE users MODIFY deployment_owner TINYINT");
        execute("CREATE TABLE IF NOT EXISTS user_login_session(session_id_hash CHAR(64) PRIMARY KEY,user_id BIGINT,session_version BIGINT,revoked_at DATETIME(6),expires_at DATETIME(6),last_seen_at DATETIME(6))");
        hash=GatewayIdentityCodec.hash(credential);
        execute("INSERT INTO users VALUES("+id+",'fixture','测试用户','USER',true,1,'ACTIVE',0)");
        execute("INSERT INTO user_login_session VALUES('"+hash+"',"+id+",0,NULL,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 DAY),UTC_TIMESTAMP(6))");
    }
    @BeforeEach void reset() {
        execute("UPDATE users SET status=1,account_status='ACTIVE',session_version=0 WHERE user_id="+id);
        execute("UPDATE user_login_session SET revoked_at=NULL,session_version=0,expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 DAY),last_seen_at=UTC_TIMESTAMP(6) WHERE session_id_hash='"+hash+"'");
    }
    @Test void validSessionUsesAuthoritativeStoreAcrossVerifierInstances() {
        var identity=store.authenticate(credential).block(Duration.ofSeconds(3));
        assertNotNull(identity); assertEquals(id,identity.userId()); assertEquals("测试用户",identity.nickname()); assertTrue(identity.owner());
        assertEquals(identity,new GatewaySessionStore(db).authenticate(credential).block(Duration.ofSeconds(3)));
    }
    @Test void revocationAndGlobalVersionChangeApplyWithoutCacheDelay() {
        execute("UPDATE user_login_session SET revoked_at=UTC_TIMESTAMP(6) WHERE session_id_hash='"+hash+"'");
        assertNull(store.authenticate(credential).block(Duration.ofSeconds(3)));
        reset(); execute("UPDATE users SET session_version=1 WHERE user_id="+id);
        assertNull(store.authenticate(credential).block(Duration.ofSeconds(3)));
    }
    @Test void absoluteExpiryIdleExpiryAndDisabledAccountsAreRejected() {
        for(String sql:java.util.List.of(
                "UPDATE user_login_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE session_id_hash='"+hash+"'",
                "UPDATE user_login_session SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 16 DAY) WHERE session_id_hash='"+hash+"'",
                "UPDATE users SET status=0 WHERE user_id="+id,
                "UPDATE users SET account_status='CANCELED' WHERE user_id="+id)) {
            reset(); execute(sql); assertNull(store.authenticate(credential).block(Duration.ofSeconds(3)));
        }
    }
    @Test void activityRefreshDoesNotExtendAbsoluteLifetime() {
        execute("UPDATE user_login_session SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 11 MINUTE) WHERE session_id_hash='"+hash+"'");
        var expiry=db.sql("SELECT expires_at FROM user_login_session WHERE session_id_hash=:hash").bind("hash",hash).map((row,m)->row.get(0,java.time.LocalDateTime.class)).one().block();
        assertNotNull(store.authenticate(credential).block(Duration.ofSeconds(3)));
        var updated=db.sql("SELECT expires_at, TIMESTAMPDIFF(SECOND,last_seen_at,UTC_TIMESTAMP(6)) age FROM user_login_session WHERE session_id_hash=:hash").bind("hash",hash).fetch().one().block();
        assertEquals(expiry,updated.get("expires_at")); assertTrue(((Number)updated.get("age")).longValue()<5);
    }
    @AfterAll void cleanup() {
        if(db!=null && hash!=null) { execute("DELETE FROM user_login_session WHERE session_id_hash='"+hash+"'"); execute("DELETE FROM users WHERE user_id="+id); }
    }
}
