package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortLinkAccessLogTest {
    @Test void accessDoesNotWaitForDatabaseAndFailuresAreMeasured() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var metrics = new SimpleMeterRegistry();
        var log = new ShortLinkAccessLog(db, new ObjectMapper(), metrics);
        log.record("PREVIEW_RESOLVE",1L,"127.0.0.1","/p/abc","abc",null,"RESOLVED");
        verifyNoInteractions(db);
        when(db.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("offline"));
        assertDoesNotThrow(log::flush);
        assertEquals(1, metrics.counter("short.link.log.dropped","reason","database").count());
        assertEquals(0, metrics.get("short.link.log.queued").gauge().value());
    }
}
