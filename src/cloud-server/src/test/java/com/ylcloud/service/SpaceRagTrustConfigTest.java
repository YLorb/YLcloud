package com.ylcloud.service;

import com.ylcloud.DTO.SpaceRagConfigUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.entity.SpaceRagConfigLog;
import com.ylcloud.mapper.SpaceRagConfigLogMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.service.space.SpacePermissionService;
import com.ylcloud.service.space.SpaceRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpaceRagTrustConfigTest {
    private final SpaceRagMapper mapper = mock(SpaceRagMapper.class);
    private final SpacePermissionService permissions = mock(SpacePermissionService.class);
    private final SpaceRagConfigLogMapper logs = mock(SpaceRagConfigLogMapper.class);
    private final SpaceRagService service = mock(SpaceRagService.class, CALLS_REAL_METHODS);
    private SpaceRagConfig current;
    private SpaceRagConfig written;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"spaceRagMapper",mapper);
        ReflectionTestUtils.setField(service,"spacePermissionService",permissions);
        ReflectionTestUtils.setField(service,"spaceRagConfigLogMapper",logs);
        current = new SpaceRagConfig();
        current.setSpaceId(1L); current.setEmbeddingModel("embed"); current.setChatModel("chat");
        current.setChunkSize(500); current.setChunkOverlap(50); current.setTopK(10);
        current.setTemperature(new BigDecimal("0.20")); current.setScoreThreshold(new BigDecimal("0.3"));
        current.setEnabled(1); current.setKnowledgeProfileEnabled(1); current.setStatus(1);
        current.setConfigRevision(3L); current.setAllowStaleDuringReindex(false); current.setHistoricalEvidenceEnabled(false);
        when(mapper.getBySpaceId(1L)).thenAnswer(call -> written == null ? current : written);
        when(mapper.updateConfig(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), any(),
                anyInt(), anyInt(), anyBoolean(), anyBoolean(), nullable(Integer.class), anyLong(), any())).thenAnswer(call -> {
            written = new SpaceRagConfig(); BeanUtils.copyProperties(current,written);
            written.setAllowStaleDuringReindex(call.getArgument(10));
            written.setHistoricalEvidenceEnabled(call.getArgument(11));
            written.setHistoryRetentionDays(call.getArgument(12));
            written.setConfigRevision(((Long) call.getArgument(13)) + 1);
            return 1;
        });
    }

    @Test void legacyUpdatePreservesNewSettings() {
        current.setAllowStaleDuringReindex(true); current.setHistoricalEvidenceEnabled(true); current.setHistoryRetentionDays(90);
        var result = service.updateConfig(1L,new SpaceRagConfigUpdateDTO(),7L);
        assertTrue(result.getAllowStaleDuringReindex()); assertTrue(result.getHistoricalEvidenceEnabled());
        assertEquals(90,result.getHistoryRetentionDays()); assertEquals(4L,result.getConfigRevision());
        verify(permissions).requireAdmin(1L,7L);
    }

    @Test void enablingHistoryRequiresExplicitPositiveRetention() {
        var dto = new SpaceRagConfigUpdateDTO(); dto.setHistoricalEvidenceEnabled(true);
        assertThrows(BaseException.class,() -> service.updateConfig(1L,dto,7L));
        dto.setHistoryRetentionDays(0);
        assertThrows(BaseException.class,() -> service.updateConfig(1L,dto,7L));
        assertNull(written);
    }

    @Test void staleClientRevisionIsRejectedWithoutWriting() {
        var dto = new SpaceRagConfigUpdateDTO(); dto.setConfigRevision(2L);
        var error = assertThrows(BaseException.class,() -> service.updateConfig(1L,dto,7L));
        assertTrue(error.getMessage().contains("其他管理员")); assertNull(written);
    }

    @Test void concurrentWriteLosingCasIsRejectedWithoutAudit() {
        when(mapper.updateConfig(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), any(),
                anyInt(), anyInt(), anyBoolean(), anyBoolean(), nullable(Integer.class), anyLong(), any())).thenReturn(0);
        assertThrows(BaseException.class,() -> service.updateConfig(1L,new SpaceRagConfigUpdateDTO(),7L));
        verifyNoInteractions(logs);
    }

    @Test void memberCannotChangeSettings() {
        doThrow(new BaseException(403,"无权修改")).when(permissions).requireAdmin(1L,8L);
        assertThrows(BaseException.class,() -> service.updateConfig(1L,new SpaceRagConfigUpdateDTO(),8L));
        verifyNoInteractions(mapper,logs);
    }

    @Test void trustChangeIsAuditedAndDisablingPreservesRetention() {
        var dto = new SpaceRagConfigUpdateDTO(); dto.setHistoricalEvidenceEnabled(true); dto.setHistoryRetentionDays(30);
        dto.setAllowStaleDuringReindex(true); dto.setConfigRevision(3L);
        var result = service.updateConfig(1L,dto,7L);
        assertTrue(result.getHistoricalEvidenceEnabled());
        var captor = ArgumentCaptor.forClass(SpaceRagConfigLog.class);
        verify(logs).insert(captor.capture());
        assertTrue(captor.getValue().getChangedFields().contains("historicalEvidenceEnabled"));
        assertTrue(captor.getValue().getAfterJson().contains("\"historyRetentionDays\":30"));
        current = written; written = null;
        var disable = new SpaceRagConfigUpdateDTO(); disable.setHistoricalEvidenceEnabled(false);
        assertEquals(30,service.updateConfig(1L,disable,7L).getHistoryRetentionDays());
    }

    @Test void nullLegacyFlagsReadAsSafeDefaults() {
        current.setAllowStaleDuringReindex(null); current.setHistoricalEvidenceEnabled(null); current.setConfigRevision(null);
        var result = service.getConfig(1L,7L);
        assertFalse(result.getAllowStaleDuringReindex()); assertFalse(result.getHistoricalEvidenceEnabled());
        assertEquals(0L,result.getConfigRevision());
    }
}
