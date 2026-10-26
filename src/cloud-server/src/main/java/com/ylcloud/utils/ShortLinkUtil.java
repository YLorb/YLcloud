package com.ylcloud.utils;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

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

    private final long nodeId;
    private final Clock clock;
    private long lastTimestamp = -1;
    private int sequence;

    public ShortLinkUtil(long nodeId) {
        this(nodeId, Clock.systemUTC());
    }

    ShortLinkUtil(long nodeId, Clock clock) {
        if (nodeId < 0 || nodeId > 1023) {
            throw new IllegalArgumentException("nodeId must be between 0 and 1023");
        }
        this.nodeId = nodeId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 生成并原子占用短码；最多尝试 10 次，存储故障直接向上传播。
     * 调用方提供共享持久存储的占用操作，例如通过唯一索引插入记录。
     * 不能用 exists 查询代替占用；重启或多节点场景的最终防重由该原子操作保证。
     * 占用记录应保留至业务完成持久化，不能在业务写入前释放。
     */
    public String generate(ShortCodeReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = toBase62(nextId());
            if (reservation.tryReserve(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to reserve a unique short code after " + MAX_ATTEMPTS + " attempts");
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
            throw new IllegalStateException("Clock moved backwards; refusing to generate a Snowflake ID");
        }
        return timestamp;
    }

    @FunctionalInterface
    public interface ShortCodeReservation {
        /** 原子占用：成功返回 true，仅短码已占用返回 false；其他错误应抛出异常。 */
        boolean tryReserve(String shortCode);
    }
}
