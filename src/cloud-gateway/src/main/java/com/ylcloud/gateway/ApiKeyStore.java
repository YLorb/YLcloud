package com.ylcloud.gateway;

import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import jakarta.annotation.PreDestroy;

@Component
public class ApiKeyStore {
    public record Principal(long keyId,long userId,Set<String> scopes,String readOnlyAfter) {}
    private record Key(long id,long userId,String hash) {}
    private final DatabaseClient db;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    private final Scheduler crypto=Schedulers.newBoundedElastic(4,32,"gateway-api-key-crypto");
    private static final String DUMMY="$2a$10$tYJdXzBP4YlqdKqZzhy2n.T4HvuvfJ4hEX9gVBxGOvALvXlqTj1uK";
    public ApiKeyStore(DatabaseClient db) { this.db=db; }
    public Mono<Principal> authenticate(String plaintext) {
        if(plaintext==null || !plaintext.matches("ylk_[A-Za-z0-9_-]{12}_[A-Za-z0-9_-]{43}")) return Mono.empty();
        return db.sql("SELECT key_id,user_id,key_hash FROM user_api_key WHERE key_prefix=:prefix")
                .bind("prefix",plaintext.substring(0,16)).map((row,m)->new Key(row.get("key_id",Long.class),row.get("user_id",Long.class),row.get("key_hash",String.class)))
                .one().defaultIfEmpty(new Key(0,0,DUMMY)).flatMap(key->Mono.fromCallable(()->passwords.matches(plaintext,key.hash()))
                    .subscribeOn(crypto).filter(Boolean::booleanValue).filter(ok->key.id()>0).flatMap(ok->active(key)));
    }
    private Mono<Principal> active(Key key) {
        // Recheck after bounded BCrypt work so a revocation while queued does not authenticate stale state.
        return db.sql("""
            SELECT k.key_id FROM user_api_key k JOIN users u ON u.user_id=k.user_id
            WHERE k.key_id=:id AND k.key_hash=:hash AND k.key_status='ACTIVE' AND k.revoked_at IS NULL
              AND (k.expires_at IS NULL OR k.expires_at>:now) AND u.status=1
              AND (u.account_status IS NULL OR u.account_status='ACTIVE')
              AND NOT EXISTS (SELECT 1 FROM site_setting WHERE setting_key='apiKey.enabled' AND LOWER(setting_value)<>'true')
            """).bind("id",key.id()).bind("hash",key.hash()).bind("now",LocalDateTime.now()).map((r,m)->r.get("key_id",Long.class)).one()
            .flatMap(id->db.sql("SELECT scope_key FROM api_key_scope WHERE key_id=:id").bind("id",id)
                .map((r,m)->r.get("scope_key",String.class)).all().collectList()
                .zipWith(db.sql("SELECT setting_value FROM site_setting WHERE setting_key='api.v1.readOnlyAfter'")
                    .map((r,m)->r.get("setting_value",String.class)).one().defaultIfEmpty(""))
                .map(value->new Principal(id,key.userId(),Set.copyOf(value.getT1()),value.getT2())));
    }
    @PreDestroy void close() { crypto.dispose(); }
}
