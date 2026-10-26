package com.ylcloud.service;

import com.ylcloud.utils.HashUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remove mappings only after the target record is physically deleted, not merely recycled/revoked. */
@Component
public class PreviewLinkCleanup {
    private final JdbcTemplate db;
    private final ShortLinkCache cache;
    private final MeterRegistry metrics;

    public PreviewLinkCleanup(JdbcTemplate db, ShortLinkCache cache, MeterRegistry metrics) {
        this.db = db; this.cache = cache; this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        // A bounded indexed sweep avoids unbounded work and ignores newly created mappings.
        var rows = db.query("SELECT short_code,target_url FROM preview_short_link WHERE checked_at<TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)) ORDER BY checked_at LIMIT 200",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)});
        for (String[] row : rows) {
            if (targetExists(row[1])) {
                db.update("UPDATE preview_short_link SET checked_at=UTC_TIMESTAMP(6) WHERE short_code=?", row[0]);
            } else {
                if (db.update("DELETE FROM preview_short_link WHERE short_code=? AND target_url=?", row[0], row[1]) == 1) {
                    cache.evict("code:" + row[0]);
                    cache.evict("target:" + HashUtil.sha256(row[1]));
                    metrics.counter("short.link.cleanup.removed").increment();
                }
            }
        }
    }

    boolean targetExists(String url) {
        String[] parts = url.split("[/?#]");
        // URL shape is strictly validated by PreviewLinkService before persistence.
        if (url.startsWith("/api/file/preview/")) {
            return exists("SELECT COUNT(*) FROM file_info WHERE file_uuid=?", parts[4]);
        }
        if (url.contains("/rag/evidence/")) {
            return exists("SELECT COUNT(*) FROM rag_evidence WHERE id=? AND space_id=?", parts[6], parts[3]);
        }
        if (url.contains("/versions/")) {
            // file_version.id is globally unique; the stream endpoint still checks space/file binding.
            return exists("SELECT COUNT(*) FROM file_version WHERE id=?", parts[7]);
        }
        if (url.startsWith("/api/space/")) {
            return exists("SELECT COUNT(*) FROM space_file WHERE id=? AND space_id=?", parts[5], parts[3]);
        }
        return true; // Never delete an unknown future URL shape automatically.
    }

    private boolean exists(String sql, Object... arguments) {
        return db.queryForObject(sql, Long.class, arguments) > 0;
    }
}
