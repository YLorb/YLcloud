package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.mapper.AdminMetricsMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminMetricsServiceTest {
    @Test void fillsMissingDaysAndSeparatesReferencesFromBlobs() {
        AdminMetricsMapper mapper = mock(AdminMetricsMapper.class);
        LocalDate today = LocalDate.of(2026,9,13);
        when(mapper.references(anyLong(),anyLong())).thenReturn(List.of(Map.of("day",today.toEpochDay(),"total",5L)));
        when(mapper.blobs(anyLong(),anyLong())).thenReturn(List.of(Map.of("day",today.toEpochDay(),"total",2L)));
        AdminMetricsService.Metrics result = new AdminMetricsService(mapper).daily(2,today);
        assertEquals("Asia/Shanghai",result.timezone());
        assertFalse(result.tokenStatisticsAvailable());
        assertEquals(2,result.days().size());
        assertEquals(0,result.days().get(0).newFiles());
        assertEquals(5,result.days().get(1).newFiles());
        assertEquals(2,result.days().get(1).newBlobs());
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        verify(mapper).users(today.minusDays(1).atStartOfDay(zone).toEpochSecond(),today.plusDays(1).atStartOfDay(zone).toEpochSecond());
    }
    @Test void boundsDateRangeBeforeDatabaseAccess() {
        AdminMetricsMapper mapper = mock(AdminMetricsMapper.class);
        AdminMetricsService service = new AdminMetricsService(mapper);
        assertThrows(BaseException.class, () -> service.daily(0));
        assertThrows(BaseException.class, () -> service.daily(91));
        verifyNoInteractions(mapper);
    }
}
