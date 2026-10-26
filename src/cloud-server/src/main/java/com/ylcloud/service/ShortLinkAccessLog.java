package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, best-effort access telemetry. Never used for authorization or quota accounting. */
@Component
public class ShortLinkAccessLog {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final ArrayBlockingQueue<Object[]> queue = new ArrayBlockingQueue<>(10000);
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "short-link-access-log");
        thread.setDaemon(true);
        return thread;
    });

    public ShortLinkAccessLog(JdbcTemplate db, ObjectMapper json, MeterRegistry metrics) {
        this.db = db;
        this.json = json;
        this.metrics = metrics;
        metrics.gauge("short.link.log.queued", queue, ArrayBlockingQueue::size);
    }

    public void record(String type, Long visitor, String ip, String address, String code, Long linkId, String outcome) {
        try {
            // Four business fields: type, visitor (user/IP), address, information. ID/time are metadata.
            String info = json.writeValueAsString(Map.of("outcome", outcome));
            Object[] row = {linkId, code, visitor, visitor == null ? ip : null,
                    LocalDateTime.now(ZoneOffset.UTC), outcome, type, address, info};
            if (!queue.offer(row)) metrics.counter("short.link.log.dropped", "reason", "queue_full").increment();
        } catch (JsonProcessingException invalid) {
            metrics.counter("short.link.log.dropped", "reason", "serialization").increment();
        }
    }

    @PostConstruct
    public void start() { writer.scheduleWithFixedDelay(this::flush, 250, 250, TimeUnit.MILLISECONDS); }

    public synchronized void flush() {
        var rows = new ArrayList<Object[]>(200);
        queue.drainTo(rows, 200);
        if (rows.isEmpty()) return;
        try {
            // A single multi-row INSERT works even when JDBC rewriteBatchedStatements is disabled.
            // Plain executeBatch would otherwise incur one autocommit/fsync per log row.
            String values = String.join(",", java.util.Collections.nCopies(rows.size(), "(?,?,?,?,?,?,?,?,?)"));
            var parameters = new ArrayList<Object>(rows.size() * 9);
            rows.forEach(row -> java.util.Collections.addAll(parameters, row));
            db.update("INSERT INTO share_link_visit(link_id,requested_code,user_id,ip,visited_at,outcome,log_type,access_address,access_info) VALUES" + values,
                    parameters.toArray());
            metrics.counter("short.link.log.written").increment(rows.size());
        } catch (org.springframework.dao.DataAccessException failure) {
            // An unknown commit result could duplicate logs on retry; report loss instead of blocking access.
            metrics.counter("short.link.log.dropped", "reason", "database").increment(rows.size());
        }
    }

    @PreDestroy
    public void close() {
        writer.shutdown();
        // Bounded shutdown: normal operation drains the queue every 250ms.
        flush();
        metrics.counter("short.link.log.dropped", "reason", "shutdown").increment(queue.size());
        queue.clear();
    }
}
