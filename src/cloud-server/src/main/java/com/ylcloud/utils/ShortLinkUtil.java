package com.ylcloud.utils;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * 雪花 ID + Base62 短码生成器，不负责 URL 拼接或访问控制。
 * 每个节点复用一个实例，不同节点配置不同的 nodeId（0..1023）。
 * ID 使用 41 位毫秒时间、10 位节点号、12 位序列号，纪元为 2026-01-01 UTC。
 * Base62 区分大小写，完整保留 ID，最长 11 位；存储时应使用区分大小写的唯一索引。
 */
public final class ShortLinkUtil {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final long EPOCH_MILLIS = 1767225600000L;
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;
    private static final int MAX_SEQUENCE = 4095;
    private static final int MAX_ATTEMPTS = 10;
    private static final long WAIT_NANOS = 100_000_000L;
    private static final long MAX_ROLLBACK_MILLIS = 15 * 60 * 1000L;

    private final long nodeId;
    private final Clock clock;
    private final Runnable requireNode;
    private final Consumer<String> events;
    private long lastTimestamp = -1;
    private int sequence;

    public ShortLinkUtil(long nodeId) {
        this(nodeId, Clock.systemUTC());
    }

    public ShortLinkUtil(long nodeId, Runnable requireNode, Consumer<String> events) {
        this(nodeId, Clock.systemUTC(), requireNode, events);
    }

    ShortLinkUtil(long nodeId, Clock clock) {
        this(nodeId, clock, () -> {}, event -> {});
    }

    ShortLinkUtil(long nodeId, Clock clock, Runnable requireNode, Consumer<String> events) {
        if (nodeId < 0 || nodeId > 1023) {
            throw new IllegalArgumentException("nodeId must be between 0 and 1023");
        }
        this.nodeId = nodeId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requireNode = Objects.requireNonNull(requireNode, "requireNode");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 生成并原子占用短码；最多尝试 10 次，存储故障直接向上传播。
     * 调用方提供共享持久存储的占用操作，例如通过唯一索引插入记录。
     * 不能用 exists 查询代替占用；重启或多节点场景的最终防重由该原子操作保证。
     * 占用记录应保留至业务完成持久化，不能在业务写入前释放。
     */
    public String generate(ShortCodeReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        events.accept("request");
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                requireNode.run();
                String code = toBase62(nextId());
                if (reservation.tryReserve(code)) {
                    events.accept("success");
                    return code;
                }
                events.accept("collision");
            }
            events.accept("exhausted");
            throw new IllegalStateException("Unable to reserve a unique short code after " + MAX_ATTEMPTS + " attempts");
        } catch (ExistingCode reused) {
            events.accept("reused");
            return reused.code;
        } catch (RuntimeException failure) {
            events.accept("failure");
            throw failure;
        }
    }

    /** 将非负 ID 编码为 Base62，不截断、不补位。 */
    public static String toBase62(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        char[] buffer = new char[11];
        int position = buffer.length;
        do {
            buffer[--position] = ALPHABET.charAt((int) (id % 62));
            id /= 62;
        } while (id != 0);
        return new String(buffer, position, buffer.length - position);
    }

    private synchronized long nextId() {
        long timestamp = timestamp();
        if (timestamp == lastTimestamp && sequence == MAX_SEQUENCE) {
            long started = System.nanoTime();
            do {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Interrupted while waiting for the next millisecond");
                }
                if (System.nanoTime() - started >= WAIT_NANOS) {
                    throw new IllegalStateException("Clock did not advance after exhausting the Snowflake sequence");
                }
                LockSupport.parkNanos(100_000L);
                timestamp = timestamp();
            } while (timestamp == lastTimestamp);
        }
        sequence = timestamp == lastTimestamp ? sequence + 1 : 0;
        lastTimestamp = timestamp;
        return (timestamp << 22) | (nodeId << 12) | sequence;
    }

    private long timestamp() {
        long now = clock.millis();
        if (now < EPOCH_MILLIS || now > EPOCH_MILLIS + MAX_TIMESTAMP) {
            throw new IllegalStateException("Clock is outside the Snowflake timestamp range");
        }
        long timestamp = now - EPOCH_MILLIS;
        if (timestamp < lastTimestamp) {
            long rollback = lastTimestamp - timestamp;
            events.accept(rollback <= MAX_ROLLBACK_MILLIS ? "clock_wait" : "clock_error");
            // Keep the high-water mark. Calls fail fast until wall time catches up, without parking HTTP threads.
            throw new ClockRollbackException(rollback, rollback > MAX_ROLLBACK_MILLIS);
        }
        return timestamp;
    }

    @FunctionalInterface
    public interface ShortCodeReservation {
        /** 原子占用：成功返回 true，仅短码已占用返回 false；其他错误应抛出异常。 */
        boolean tryReserve(String shortCode);
    }

    /** A reservation may discover an already committed mapping for the same target. */
    public static final class ExistingCode extends RuntimeException {
        private final String code;
        public ExistingCode(String code) {
            super(null, null, false, false);
            if (code == null || !code.matches("[A-Za-z0-9]{1,11}")) throw new IllegalArgumentException("Invalid existing short code");
            this.code = code;
        }
    }

    public static final class ClockRollbackException extends IllegalStateException {
        private final long rollbackMillis;
        private final boolean excessive;
        public ClockRollbackException(long rollbackMillis, boolean excessive) {
            super(excessive ? "Clock rollback exceeds 15 minutes" : "Waiting for clock recovery");
            this.rollbackMillis = rollbackMillis;
            this.excessive = excessive;
        }
        public long rollbackMillis() { return rollbackMillis; }
        public boolean excessive() { return excessive; }
    }
}
