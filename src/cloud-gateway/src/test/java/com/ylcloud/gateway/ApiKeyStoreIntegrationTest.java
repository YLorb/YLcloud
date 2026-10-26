package com.ylcloud.gateway;

import io.r2dbc.spi.ConnectionFactories;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GATEWAY_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyStoreIntegrationTest {
    private DatabaseClient db;
    private ApiKeyStore store;
    private final long id=20_000_000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%1_000_000);
    private final String key="ylk_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_"+"t".repeat(43);
    private void sql(String sql) { db.sql(sql).fetch().rowsUpdated().block(Duration.ofSeconds(10)); }
    @BeforeAll void prepare() {
        String url=System.getenv("GATEWAY_TEST_DB_URL");
        if(!url.contains("/gateway_session_test")) throw new IllegalArgumentException("isolated schema required");
        db=DatabaseClient.create(ConnectionFactories.get(url)); store=new ApiKeyStore(db);
        sql("CREATE TABLE IF NOT EXISTS users(user_id BIGINT PRIMARY KEY,username VARCHAR(100),nickname VARCHAR(100),role VARCHAR(20),deployment_owner TINYINT,status INT,account_status VARCHAR(20),session_version BIGINT)");
        sql("CREATE TABLE IF NOT EXISTS user_api_key(key_id BIGINT PRIMARY KEY,user_id BIGINT,key_prefix VARCHAR(30),key_hash VARCHAR(100),key_status VARCHAR(20),revoked_at DATETIME,expires_at DATETIME)");
        sql("CREATE TABLE IF NOT EXISTS api_key_scope(key_id BIGINT,scope_key VARCHAR(40))");
        sql("CREATE TABLE IF NOT EXISTS site_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value TEXT)");
        sql("INSERT INTO users VALUES("+id+",'api-key-fixture','fixture','USER',0,1,'ACTIVE',0)");
        db.sql("INSERT INTO user_api_key VALUES(:id,:id,:prefix,:hash,'ACTIVE',NULL,NULL)").bind("id",id).bind("prefix",key.substring(0,16)).bind("hash",new BCryptPasswordEncoder().encode(key)).fetch().rowsUpdated().block();
        sql("INSERT INTO api_key_scope VALUES("+id+",'DRIVE_READ')");
    }
    @BeforeEach void reset() {
        sql("UPDATE users SET status=1,account_status='ACTIVE' WHERE user_id="+id);
        sql("UPDATE user_api_key SET key_status='ACTIVE',revoked_at=NULL,expires_at=NULL WHERE key_id="+id);
    }
    private ApiKeyStore.Principal authenticate(String value) { return store.authenticate(value).block(Duration.ofSeconds(5)); }
    @Test void realBcryptAndScopeMappingWorkAndWrongSecretsFail() {
        var principal=authenticate(key); assertNotNull(principal); assertEquals(id,principal.userId()); assertTrue(principal.scopes().contains("DRIVE_READ"));
        assertNull(authenticate(key.substring(0,59)+"x")); assertNull(authenticate("malformed"));
        assertNull(authenticate("ylk_"+"z".repeat(12)+"_"+"x".repeat(43)));
    }
    @Test void revocationAndAccountLifecycleHaveNoCacheDelay() {
        for(String change:java.util.List.of("UPDATE user_api_key SET key_status='REVOKED' WHERE key_id=","UPDATE user_api_key SET revoked_at=NOW() WHERE key_id=","UPDATE users SET status=0 WHERE user_id=","UPDATE users SET account_status='CANCELED' WHERE user_id=")) {
            reset(); assertNotNull(authenticate(key)); sql(change+id); assertNull(authenticate(key));
        }
    }
    @Test void expirationUsesSameApplicationClockAsBackendRatherThanDatabaseTimezone() {
        for(boolean expired:new boolean[]{false,true}) {
            db.sql("UPDATE user_api_key SET expires_at=:expiry WHERE key_id=:id").bind("id",id).bind("expiry",LocalDateTime.now().plusSeconds(expired ? -10 : 60)).fetch().rowsUpdated().block();
            assertEquals(!expired,authenticate(key)!=null);
        }
    }
    @AfterAll void cleanup() {
        if(store!=null) store.close();
        if(db!=null) { sql("DELETE FROM api_key_scope WHERE key_id="+id); sql("DELETE FROM user_api_key WHERE key_id="+id); sql("DELETE FROM users WHERE user_id="+id); }
    }
}
