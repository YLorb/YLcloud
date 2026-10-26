package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.utils.HashUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShortLinkAbuseGuard {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local banned = redis.call('TTL', KEYS[2])
            if banned > 0 then return banned end
            if ARGV[1] == 'check' then return 0 end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'EX', ARGV[4])
              redis.call('DEL', KEYS[1])
              return tonumber(ARGV[4])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final int threshold, windowSeconds, banSeconds;

    public ShortLinkAbuseGuard(StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${ylcloud.short-link.abuse.threshold:10}") int threshold,
            @Value("${ylcloud.short-link.abuse.window-seconds:60}") int windowSeconds,
            @Value("${ylcloud.short-link.abuse.ban-seconds:900}") int banSeconds) {
        if (threshold < 1 || windowSeconds < 1 || banSeconds < 1) throw new IllegalArgumentException("Invalid short-link abuse policy");
        this.redis = redis; this.metrics = metrics;
        this.threshold = threshold; this.windowSeconds = windowSeconds; this.banSeconds = banSeconds;
    }

    public long check(String ip) { return execute(ip, "check"); }
    public long missing(String ip) {
        metrics.counter("short.link.missing").increment();
        return execute(ip, "miss");
    }

    private long execute(String ip, String operation) {
        String tag = HashUtil.sha256(ip);
        try {
            Long seconds = redis.execute(SCRIPT, List.of("ylcloud:short-link:abuse:{" + tag + "}:count",
                    "ylcloud:short-link:abuse:{" + tag + "}:ban"), operation,
                    Integer.toString(threshold), Integer.toString(windowSeconds), Integer.toString(banSeconds));
            if (seconds == null) throw new BaseException(503, "短链接访问保护暂不可用");
            if (seconds > 0) metrics.counter("short.link.blocked").increment();
            return seconds;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            metrics.counter("short.link.abuse.unavailable").increment();
            throw new BaseException(503, "短链接访问保护暂不可用");
        }
    }

    public static class Missing extends RuntimeException { }
}
