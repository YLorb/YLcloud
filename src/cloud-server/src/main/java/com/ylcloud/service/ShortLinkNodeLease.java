package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A DB-backed lease prevents concurrently live instances from sharing a node ID. */
@Component
@DependsOnDatabaseInitialization
public class ShortLinkNodeLease {
    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    private final long nodeId;
    private final String owner = UUID.randomUUID().toString();
    private volatile long deadline;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "short-link-node-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ShortLinkNodeLease(JdbcTemplate db, MeterRegistry metrics,
                              @Value("${ylcloud.short-link.node-id:0}") long nodeId) {
        if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("Invalid short-link node ID");
        this.db = db;
        this.metrics = metrics;
        this.nodeId = nodeId;
        metrics.gauge("short.link.node.lease.active", this, lease -> lease.active() ? 1 : 0);
    }

    @PostConstruct
    public void start() {
        acquire();
        // Existing maintenance jobs must never delay renewal of the generator's lease.
        heartbeat.scheduleWithFixedDelay(this::renew, 5, 5, TimeUnit.SECONDS);
    }

    public void acquire() {
        long started = System.nanoTime();
        db.update("INSERT IGNORE INTO short_link_node(node_id,owner,lease_until) VALUES(?,?,TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)))", nodeId, owner);
        int changed = db.update("UPDATE short_link_node SET owner=?,lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE node_id=? AND (owner=? OR lease_until<UTC_TIMESTAMP(6))",
                owner, nodeId, owner);
        if (changed != 1) throw new IllegalStateException("Short-link node ID is already in use: " + nodeId);
        deadline = started + Duration.ofSeconds(25).toNanos();
    }

    public void renew() {
        long started = System.nanoTime();
        try {
            int changed = db.update("UPDATE short_link_node SET lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE node_id=? AND owner=? AND lease_until>UTC_TIMESTAMP(6)", nodeId, owner);
            deadline = changed == 1 ? started + Duration.ofSeconds(25).toNanos() : 0;
            if (changed != 1) metrics.counter("short.link.node.lease.failure").increment();
        } catch (org.springframework.dao.DataAccessException failure) {
            deadline = 0;
            metrics.counter("short.link.node.lease.failure").increment();
        }
    }

    public void requireActive() {
        if (!active()) throw new BaseException(503, "短链接节点租约不可用，请稍后重试");
    }

    private boolean active() { return deadline != 0 && System.nanoTime() < deadline; }

    @PreDestroy
    public void release() {
        heartbeat.shutdownNow();
        deadline = 0;
        try { db.update("DELETE FROM short_link_node WHERE node_id=? AND owner=?", nodeId, owner); }
        catch (org.springframework.dao.DataAccessException failure) { /* Lease expires without renewal. */ }
    }
}
