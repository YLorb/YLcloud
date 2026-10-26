package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShortLinkNodeLeaseTest {
    @Test void duplicateNodeFailsAndLostLeaseStopsGeneration() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var lease = new ShortLinkNodeLease(db, new SimpleMeterRegistry(), 7);
        assertThrows(IllegalStateException.class, lease::acquire);
        assertThrows(BaseException.class, lease::requireActive);
        when(db.update(startsWith("UPDATE short_link_node SET owner="), any(), any(), any())).thenReturn(1);
        lease.acquire();
        assertDoesNotThrow(lease::requireActive);
        lease.renew(); // UPDATE count 0: node ownership was lost.
        assertThrows(BaseException.class, lease::requireActive);
    }
}
