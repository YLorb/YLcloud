package com.ylcloud.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Redis stores mappings only, never authorization decisions or credentials. */
@Component
public class ShortLinkCache {
    public static final String MISSING = "!missing";
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final String prefix;
    private final AtomicLong unavailableUntil = new AtomicLong();

    public ShortLinkCache(StringRedisTemplate redis, MeterRegistry metrics,
                          @Value("${ylcloud.short-link.cache-prefix:ylcloud:short-link:}") String prefix) {
        this.redis = redis;
        this.metrics = metrics;
        this.prefix = prefix;
    }

    public String get(String key) {
        if (System.nanoTime() < unavailableUntil.get()) return null;
        try {
            String value = redis.opsForValue().get(prefix + key);
            metrics.counter("short.link.cache", "result", value == null ? "miss" : "hit").increment();
            return value;
        } catch (org.springframework.dao.DataAccessException failure) {
            failed();
            return null;
        }
    }

    public void put(String key, String value) {
        afterCommit(() -> {
            if (System.nanoTime() < unavailableUntil.get()) return;
            try {
                long seconds = MISSING.equals(value) ? 5 : ThreadLocalRandom.current().nextLong(540, 661);
                redis.opsForValue().set(prefix + key, value, Duration.ofSeconds(seconds));
            } catch (org.springframework.dao.DataAccessException failure) {
                failed();
            }
        });
    }

    public void evict(String key) {
        afterCommit(() -> {
            try { redis.delete(prefix + key); }
            catch (org.springframework.dao.DataAccessException failure) { failed(); }
        });
    }

    private void failed() {
        unavailableUntil.set(System.nanoTime() + Duration.ofSeconds(5).toNanos());
        metrics.counter("short.link.cache", "result", "error").increment();
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
