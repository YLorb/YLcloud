package com.ylcloud.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SlidingWindowLimiter {
    private final ReactiveStringRedisTemplate redis;
    private final GatewaySettings settings;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    public SlidingWindowLimiter(ReactiveStringRedisTemplate redis, GatewaySettings settings) {
        this.redis = redis; this.settings = settings;
        script.setLocation(new ClassPathResource("sliding-window.lua"));
        script.setResultType(List.class);
    }
    public Mono<Decision> acquire(String address) {
        String tag = hash(address);
        return redis.execute(script, List.of(settings.keyPrefix() + "{admission}:" + tag,
                        settings.keyPrefix() + "{admission}:initialized"),
                        List.of(UUID.randomUUID().toString()))
                .single().timeout(settings.redisTimeout())
                .map(result -> {
                    if (((Number) result.get(0)).longValue() < 0) throw new IllegalStateException("Redis admission history is warming up");
                    return new Decision(((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue());
                });
    }
    private static String hash(String address) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Decision(boolean allowed, long retryAfterSeconds) {}
}
