package com.ylcloud.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortLinkCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ShortLinkCache cache = new ShortLinkCache(redis, metrics, "test:");

    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    @Test void writesOnlyAfterCommitAndRollbackCannotPublishMapping() {
        when(redis.opsForValue()).thenReturn(values);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        cache.put("code:abc", "/api/space/1/files/2/preview");
        verifyNoInteractions(values);
        // Rolling back discards the registered afterCommit callback.
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        cache.put("code:def", "/api/space/1/files/3/preview");
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(values, never()).set(eq("test:code:abc"), anyString(), any(Duration.class));
        verify(values).set(eq("test:code:def"), eq("/api/space/1/files/3/preview"), any(Duration.class));
    }

    @Test void negativeCacheHasShortTtlAndOutageOpensCircuit() {
        when(redis.opsForValue()).thenReturn(values);
        cache.put("code:missing", ShortLinkCache.MISSING);
        verify(values).set("test:code:missing", ShortLinkCache.MISSING, Duration.ofSeconds(5));
        when(values.get("test:code:a")).thenThrow(new DataAccessResourceFailureException("offline"));
        assertNull(cache.get("code:a"));
        assertNull(cache.get("code:a"));
        verify(values, times(1)).get("test:code:a");
        assertEquals(1, metrics.counter("short.link.cache", "result", "error").count());
    }
}
