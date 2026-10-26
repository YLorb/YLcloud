package com.ylcloud.utils;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortLinkUtilTest {
    private static final long NOW = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli();

    private ShortLinkUtil fixed(long node) {
        return new ShortLinkUtil(node, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void encodesWithoutTruncation() {
        assertEquals("0", ShortLinkUtil.toBase62(0));
        assertEquals("z", ShortLinkUtil.toBase62(35));
        assertEquals("A", ShortLinkUtil.toBase62(36));
        assertEquals("Z", ShortLinkUtil.toBase62(61));
        assertEquals("10", ShortLinkUtil.toBase62(62));
        assertEquals("aZl8N0y58M7", ShortLinkUtil.toBase62(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ShortLinkUtil.toBase62(-1));
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ShortLinkUtil(-1));
        assertThrows(IllegalArgumentException.class, () -> new ShortLinkUtil(1024));
        assertThrows(NullPointerException.class, () -> fixed(0).generate(null));
    }

    @Test
    void reservationProtectsAgainstRestartAndDuplicateNodeConfiguration() {
        Set<String> reserved = new HashSet<>();
        String first = fixed(1).generate(reserved::add);
        String restarted = fixed(1).generate(reserved::add);
        String otherNode = fixed(2).generate(reserved::add);
        assertEquals(3, Set.of(first, restarted, otherNode).size());
        assertEquals(3, reserved.size());
    }

    @Test
    void retriesConflictsButBoundsAttemptsAndPropagatesStorageFailures() {
        AtomicInteger attempts = new AtomicInteger();
        Set<String> candidates = new HashSet<>();
        assertThrows(IllegalStateException.class, () -> fixed(0).generate(code -> {
            candidates.add(code);
            attempts.incrementAndGet();
            return false;
        }));
        assertEquals(10, attempts.get());
        assertEquals(10, candidates.size());
        RuntimeException failure = new RuntimeException("storage unavailable");
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> fixed(0).generate(code -> { throw failure; })));
    }

    @Test
    void concurrentCallsAreUniqueWithinOneMillisecond() throws Exception {
        ShortLinkUtil generator = fixed(1023);
        Set<String> reserved = ConcurrentHashMap.newKeySet();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = IntStream.range(0, 4096)
                    .mapToObj(i -> (Callable<String>) () -> generator.generate(reserved::add)).toList();
            Set<String> returned = new HashSet<>();
            for (var result : executor.invokeAll(tasks)) {
                assertTrue(returned.add(result.get()));
            }
            assertEquals(4096, reserved.size());
            assertThrows(IllegalStateException.class, () -> generator.generate(reserved::add));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sequenceResetsWhenClockAdvances() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(NOW);
        ShortLinkUtil generator = new ShortLinkUtil(0, clock);
        Set<String> reserved = new HashSet<>();
        for (int i = 0; i < 4096; i++) {
            generator.generate(reserved::add);
        }
        when(clock.millis()).thenReturn(NOW, NOW + 1);
        generator.generate(reserved::add);
        assertEquals(4097, reserved.size());
    }

    @Test
    void rejectsClockRollbackAndOutOfRangeTime() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(NOW, NOW - 1);
        ShortLinkUtil generator = new ShortLinkUtil(0, clock);
        generator.generate(code -> true);
        assertThrows(IllegalStateException.class, () -> generator.generate(code -> true));
        for (long millis : new long[]{0, 1767225600000L + (1L << 41)}) {
            ShortLinkUtil invalid = new ShortLinkUtil(0,
                    Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
            assertThrows(IllegalStateException.class, () -> invalid.generate(code -> true));
        }
    }

    @Test
    void rollbackPausesWithoutBlockingAndAutomaticallyRecovers() {
        Clock clock = mock(Clock.class);
        var events = new java.util.ArrayList<String>();
        when(clock.millis()).thenReturn(NOW, NOW - 900_000, NOW - 900_001, NOW);
        ShortLinkUtil generator = new ShortLinkUtil(1, clock, () -> {}, events::add);
        String original = generator.generate(code -> true);
        var shortRollback = assertThrows(ShortLinkUtil.ClockRollbackException.class, () -> generator.generate(code -> true));
        assertFalse(shortRollback.excessive());
        assertEquals(900_000, shortRollback.rollbackMillis());
        var longRollback = assertThrows(ShortLinkUtil.ClockRollbackException.class, () -> generator.generate(code -> true));
        assertTrue(longRollback.excessive());
        assertNotEquals(original, generator.generate(code -> true));
        assertTrue(events.containsAll(java.util.List.of("clock_wait", "clock_error", "failure", "success")));
    }

    @Test
    void existingTargetIsReuseNotGenerationFailure() {
        var events = new java.util.ArrayList<String>();
        ShortLinkUtil generator = new ShortLinkUtil(1, Clock.systemUTC(), () -> {}, events::add);
        assertEquals("abc", generator.generate(code -> { throw new ShortLinkUtil.ExistingCode("abc"); }));
        assertTrue(events.contains("reused"));
        assertFalse(events.contains("failure"));
    }
}
