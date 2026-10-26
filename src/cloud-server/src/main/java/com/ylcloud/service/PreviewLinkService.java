package com.ylcloud.service;

import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.ShortLinkUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.ylcloud.Exception.BaseException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 持久化预览地址别名；短码不是访问凭证，目标接口仍执行原有鉴权。 */
@Service
public class PreviewLinkService {
    private static final Pattern TARGET = Pattern.compile(
            "(?:/api/file/preview/[A-Za-z0-9_-]+/stream\\?parentId=(?:[0-9]+|null)"
            + "|/api/space/[0-9]+/files/[0-9]+(?:/versions/[0-9]+)?/preview(?:/stream)?"
            + "|/api/space/[0-9]+/rag/evidence/[A-Za-z0-9_-]+/preview)(?:#page=[1-9][0-9]*)?");
    private final JdbcTemplate db;
    private final ShortLinkUtil generator;
    private final ShortLinkCache cache;
    private final MeterRegistry metrics;
    private final Semaphore databaseSlots = new Semaphore(32);
    private final ReentrantLock[] stripes = new ReentrantLock[256];

    public PreviewLinkService(JdbcTemplate db, ShortLinkUtil generator) {
        this(db, generator, null, new SimpleMeterRegistry());
    }

    @Autowired
    public PreviewLinkService(JdbcTemplate db, ShortLinkUtil generator, ShortLinkCache cache, MeterRegistry metrics) {
        this.db = db;
        this.generator = generator;
        this.cache = cache;
        this.metrics = metrics;
        java.util.Arrays.setAll(stripes, i -> new ReentrantLock());
    }

    public String shorten(String target) {
        validateTarget(target);
        String hash = HashUtil.sha256(target);
        String cached = cache == null ? null : cache.get("target:" + hash);
        if (cached != null && cached.matches("[A-Za-z0-9]{1,11}")) return "/p/" + cached;
        String existing = findByTarget(hash, target, false);
        if (existing != null) return remember(existing, hash, target);
        String code = generator.generate(candidate -> {
            try {
                db.update("INSERT INTO preview_short_link(short_code,target_hash,target_url) VALUES(?,?,?)",
                        candidate, hash, target);
                return true;
            } catch (DuplicateKeyException duplicate) {
                // Locking read sees a concurrently committed row even in REPEATABLE READ.
                String winner = findByTarget(hash, target, true);
                if (winner != null) {
                    throw new ShortLinkUtil.ExistingCode(winner);
                }
                return false; // Only the candidate short code collided: let the utility retry.
            }
        });
        return remember(code, hash, target);
    }

    private String remember(String code, String hash, String target) {
        if (cache != null) {
            cache.put("target:" + hash, code);
            cache.put("code:" + code, target);
        }
        return "/p/" + code;
    }

    public String resolve(String code) {
        if (code == null || !code.matches("[A-Za-z0-9]{1,11}")) {
            throw new ShortLinkAbuseGuard.Missing();
        }
        long started = System.nanoTime();
        try {
            String cached = cache == null ? null : cache.get("code:" + code);
            if (cached != null) return checkedCached(cached);
            ReentrantLock lock = stripes[Math.floorMod(code.hashCode(), stripes.length)];
            if (!lock.tryLock(50, TimeUnit.MILLISECONDS)) throw busy();
            try {
                cached = cache == null ? null : cache.get("code:" + code);
                if (cached != null) return checkedCached(cached);
                if (!databaseSlots.tryAcquire()) throw busy();
                try {
                    metrics.counter("short.link.resolve.database").increment();
                    var targets = db.query("SELECT target_url FROM preview_short_link WHERE short_code=?",
                            (rs, row) -> rs.getString(1), code);
                    String target = targets.isEmpty() ? ShortLinkCache.MISSING : targets.get(0);
                    if (cache != null) cache.put("code:" + code, target);
                    return checkedCached(target);
                } finally { databaseSlots.release(); }
            } finally { lock.unlock(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw busy();
        } finally {
            metrics.timer("short.link.resolve.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private String checkedCached(String target) {
        if (ShortLinkCache.MISSING.equals(target)) throw new ShortLinkAbuseGuard.Missing();
        validateTarget(target);
        return target;
    }

    private BaseException busy() {
        metrics.counter("short.link.resolve.overloaded").increment();
        return new BaseException(503, "预览解析繁忙，请稍后重试");
    }

    private String findByTarget(String hash, String target, boolean currentRead) {
        var rows = db.query("SELECT short_code,target_url FROM preview_short_link WHERE target_hash=?"
                        + (currentRead ? " FOR SHARE" : ""),
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, hash);
        if (rows.isEmpty()) return null;
        if (!target.equals(rows.get(0)[1])) throw new IllegalStateException("Preview target hash collision");
        return rows.get(0)[0];
    }

    private static void validateTarget(String target) {
        if (target == null || !TARGET.matcher(target).matches()) {
            throw new IllegalArgumentException("不支持的预览地址");
        }
    }

}
