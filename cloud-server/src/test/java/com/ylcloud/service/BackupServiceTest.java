package com.ylcloud.service;

import com.ylcloud.DTO.RestoreVerificationDTO;
import com.ylcloud.entity.BackupRun;
import com.ylcloud.mapper.BackupMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {
    @Test
    void archiveRecordStopsAtVerifyingAndDoesNotRotateReadyBackup() {
        BackupMapper mapper = mock(BackupMapper.class);
        BackupService service = new BackupService(mapper);

        var result = service.recordBackupCompletion(
                "run-1", "/backup.enc", 10L, "a".repeat(64), "key-1", "{}");

        assertEquals("VERIFYING", result.getStatus());
        assertNull(result.getPublishedAt());
        verify(mapper).insertBackup(any());
        verify(mapper, never()).markExpired(any(), any());
    }

    @Test
    void allIsolatedRestoreChecksPublishBackupThenRotate() {
        BackupMapper mapper = mock(BackupMapper.class);
        BackupRun verifying = backup(1L, "VERIFYING");
        BackupRun ready = backup(1L, "READY");
        when(mapper.getBackupById(1L)).thenReturn(verifying, ready);
        when(mapper.markReady(eq(1L), any(), any(), any())).thenReturn(1);
        when(mapper.listReadyBackups(100)).thenReturn(List.of(ready));
        BackupService service = new BackupService(mapper);

        var result = service.recordRestoreVerification(1L, success());

        assertEquals("READY", result.getStatus());
        verify(mapper).insertVerification(any());
        verify(mapper).markReady(eq(1L), any(), any(), any());
    }

    @Test
    void failedRestoreNeverExpiresPreviousReadyBackup() {
        BackupMapper mapper = mock(BackupMapper.class);
        when(mapper.getBackupById(2L)).thenReturn(backup(2L, "VERIFYING"), backup(2L, "FAILED"));
        BackupService service = new BackupService(mapper);
        RestoreVerificationDTO failed = success();
        failed.setQdrantRestored(false);
        failed.setErrorMessage("qdrant unavailable");

        var result = service.recordRestoreVerification(2L, failed);

        assertEquals("FAILED", result.getStatus());
        verify(mapper).insertVerification(any());
        verify(mapper).markFailed(eq(2L), eq("qdrant unavailable"), any(), any());
        verify(mapper, never()).markExpired(any(), any());
        verify(mapper, never()).markReady(any(), any(), any(), any());
    }

    @Test
    void productionEnvironmentCannotClaimIsolatedRestoreSuccess() {
        BackupMapper mapper = mock(BackupMapper.class);
        when(mapper.getBackupById(2L)).thenReturn(backup(2L, "VERIFYING"), backup(2L, "FAILED"));
        BackupService service = new BackupService(mapper);
        RestoreVerificationDTO dto = success();
        dto.setRestoreEnvironment("PRODUCTION");

        assertEquals("FAILED", service.recordRestoreVerification(2L, dto).getStatus());
        verify(mapper, never()).markReady(any(), any(), any(), any());
    }

    private static RestoreVerificationDTO success() {
        RestoreVerificationDTO dto = new RestoreVerificationDTO();
        dto.setVerificationKey("verify-1");
        dto.setStatus("SUCCESS");
        dto.setRestoreEnvironment("ISOLATED_DOCKER");
        dto.setMysqlRestored(true);
        dto.setMinioRestored(true);
        dto.setQdrantRestored(true);
        dto.setConfigRestored(true);
        dto.setBusinessSampleCheck(true);
        return dto;
    }

    private static BackupRun backup(Long id, String status) {
        BackupRun backup = new BackupRun();
        backup.setId(id);
        backup.setRunKey("run-" + id);
        backup.setStatus(status);
        return backup;
    }
}
