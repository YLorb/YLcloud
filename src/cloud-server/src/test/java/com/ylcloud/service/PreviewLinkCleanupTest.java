package com.ylcloud.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewLinkCleanupTest {
    @Test void resolvesSourceIdentityWithoutDeletingSoftDeletedFiles() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var cleanup = new PreviewLinkCleanup(db, mock(ShortLinkCache.class), new SimpleMeterRegistry());
        when(db.queryForObject("SELECT COUNT(*) FROM file_info WHERE file_uuid=?", Long.class, "uuid-1")).thenReturn(1L);
        assertTrue(cleanup.targetExists("/api/file/preview/uuid-1/stream?parentId=0"));
        when(db.queryForObject("SELECT COUNT(*) FROM space_file WHERE id=? AND space_id=?", Long.class, "2", "1")).thenReturn(1L);
        assertTrue(cleanup.targetExists("/api/space/1/files/2/preview/stream"));
        when(db.queryForObject("SELECT COUNT(*) FROM file_version WHERE id=?", Long.class, "3")).thenReturn(1L);
        assertTrue(cleanup.targetExists("/api/space/1/files/2/versions/3/preview"));
        when(db.queryForObject("SELECT COUNT(*) FROM rag_evidence WHERE id=? AND space_id=?", Long.class, "ev-1", "1")).thenReturn(0L);
        assertFalse(cleanup.targetExists("/api/space/1/rag/evidence/ev-1/preview#page=2"));
    }
}
