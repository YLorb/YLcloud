package com.ylcloud.service;

import com.ylcloud.VO.BackupRunVO;
import com.ylcloud.entity.BackupRun;
import com.ylcloud.entity.RestoreVerification;
import com.ylcloud.DTO.RestoreVerificationDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.mapper.BackupMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TASK-013: 备份管理服务。
 * 提供备份状态查询和管理 API。
 * 实际备份由 scripts/backup.sh 执行。
 */
@Service
@Slf4j
public class BackupService {
    private final BackupMapper backupMapper;

    public BackupService(BackupMapper backupMapper) {
        this.backupMapper = backupMapper;
    }

    /**
     * 获取 READY 备份列表。
     */
    public List<BackupRunVO> listReadyBackups(int limit) {
        return backupMapper.listReadyBackups(Math.min(limit, 50)).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 获取最近备份列表。
     */
    public List<BackupRunVO> listRecentBackups(int limit) {
        return backupMapper.listRecentBackups(Math.min(limit, 50)).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 获取备份详情。
     */
    public BackupRunVO getBackup(Long id) {
        BackupRun backup = backupMapper.getBackupById(id);
        return backup != null ? toVO(backup) : null;
    }

    /**
     * 记录备份完成（由外部脚本调用）。
     */
    public BackupRunVO recordBackupCompletion(String runKey, String archivePath, Long archiveSize,
                                               String archiveHash, String encryptionKeyId, String manifestJson) {
        if (runKey == null || runKey.isBlank() || archivePath == null || archivePath.isBlank()
                || archiveSize == null || archiveSize <= 0
                || archiveHash == null || !archiveHash.matches("(?i)[0-9a-f]{64}")) {
            throw new BaseException("备份归档元数据无效");
        }
        if (encryptionKeyId == null || encryptionKeyId.isBlank()) {
            throw new BaseException("备份加密密钥标识不能为空");
        }
        BackupRun existing = backupMapper.getBackupByKey(runKey);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            if ("READY".equals(existing.getStatus())) return toVO(existing);
            existing.setStatus("VERIFYING");
            existing.setArchivePath(archivePath);
            existing.setArchiveSizeBytes(archiveSize);
            existing.setArchiveHash(archiveHash);
            existing.setEncryptionKeyId(encryptionKeyId);
            existing.setManifestJson(manifestJson);
            existing.setFinishedAt(now);
            existing.setVerifiedAt(null);
            existing.setPublishedAt(null);
            existing.setUpdatedAt(now);
            backupMapper.updateBackup(existing);
            return toVO(existing);
        }

        BackupRun backup = new BackupRun();
        backup.setRunKey(runKey);
        backup.setBackupType("FULL");
        backup.setStatus("VERIFYING");
        backup.setArchivePath(archivePath);
        backup.setArchiveSizeBytes(archiveSize);
        backup.setArchiveHash(archiveHash);
        backup.setEncryptionKeyId(encryptionKeyId);
        backup.setManifestJson(manifestJson);
        backup.setStartedAt(now);
        backup.setFinishedAt(now);
        backup.setVerifiedAt(null);
        backup.setPublishedAt(null);
        backup.setCreatedAt(now);
        backup.setUpdatedAt(now);
        backupMapper.insertBackup(backup);

        return toVO(backup);
    }

    /**
     * 记录隔离恢复结果。只有五项验证全部成功，VERIFYING 才能原子转为 READY。
     */
    public BackupRunVO recordRestoreVerification(Long backupId, RestoreVerificationDTO dto) {
        BackupRun backup = backupMapper.getBackupById(backupId);
        if (backup == null) throw new BaseException("备份不存在");
        if (dto == null || dto.getVerificationKey() == null || dto.getVerificationKey().isBlank()) {
            throw new BaseException("恢复验证标识不能为空");
        }
        RestoreVerification duplicate = backupMapper.getVerificationByKey(dto.getVerificationKey());
        if (duplicate != null) return toVO(backup);
        if (!"VERIFYING".equals(backup.getStatus())) {
            throw new BaseException("只有 VERIFYING 备份可以提交恢复验证");
        }
        boolean isolated = dto.getRestoreEnvironment() != null &&
                dto.getRestoreEnvironment().toUpperCase().startsWith("ISOLATED");
        boolean checksPassed = Boolean.TRUE.equals(dto.getMysqlRestored())
                && Boolean.TRUE.equals(dto.getMinioRestored())
                && Boolean.TRUE.equals(dto.getQdrantRestored())
                && Boolean.TRUE.equals(dto.getConfigRestored())
                && Boolean.TRUE.equals(dto.getBusinessSampleCheck());
        boolean success = "SUCCESS".equalsIgnoreCase(dto.getStatus()) && isolated && checksPassed;
        LocalDateTime now = LocalDateTime.now();
        RestoreVerification verification = new RestoreVerification();
        verification.setBackupRunId(backupId);
        verification.setVerificationKey(dto.getVerificationKey());
        verification.setStatus(success ? "SUCCESS" : "FAILED");
        verification.setRestoreEnvironment(dto.getRestoreEnvironment());
        verification.setMysqlRestored(Boolean.TRUE.equals(dto.getMysqlRestored()));
        verification.setMinioRestored(Boolean.TRUE.equals(dto.getMinioRestored()));
        verification.setQdrantRestored(Boolean.TRUE.equals(dto.getQdrantRestored()));
        verification.setConfigRestored(Boolean.TRUE.equals(dto.getConfigRestored()));
        verification.setBusinessSampleCheck(Boolean.TRUE.equals(dto.getBusinessSampleCheck()));
        verification.setStartedAt(now);
        verification.setFinishedAt(now);
        verification.setLastError(success ? null : truncate(dto.getErrorMessage()));
        verification.setCreatedAt(now);
        verification.setUpdatedAt(now);
        backupMapper.insertVerification(verification);
        if (!success) {
            backupMapper.markFailed(backupId,
                    truncate(dto.getErrorMessage() == null ? "隔离恢复验证未全部通过" : dto.getErrorMessage()),
                    now, now);
            return toVO(backupMapper.getBackupById(backupId));
        }
        if (backupMapper.markReady(backupId, now, now, now) != 1) {
            throw new BaseException("备份状态已变化，无法发布");
        }
        rotateOldBackups();
        return toVO(backupMapper.getBackupById(backupId));
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /**
     * 轮换旧备份，保留指定数量的 READY 备份。
     */
    private void rotateOldBackups() {
        List<BackupRun> readyBackups = backupMapper.listReadyBackups(100);
        int maxKeep = 1; // 默认保留1份

        for (int i = maxKeep; i < readyBackups.size(); i++) {
            BackupRun old = readyBackups.get(i);
            backupMapper.markExpired(old.getId(), LocalDateTime.now());
            log.info("Expired old backup: id={}, runKey={}", old.getId(), old.getRunKey());
        }
    }

    /**
     * 获取备份统计。
     */
    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
                "readyBackups", backupMapper.countReadyBackups(),
                "latestBackup", getLatestBackupInfo()
        );
    }

    private java.util.Map<String, Object> getLatestBackupInfo() {
        List<BackupRun> ready = backupMapper.listReadyBackups(1);
        if (ready.isEmpty()) {
            return java.util.Map.of("exists", false);
        }
        BackupRun latest = ready.get(0);
        return java.util.Map.of(
                "exists", true,
                "id", latest.getId(),
                "runKey", latest.getRunKey(),
                "publishedAt", latest.getPublishedAt() != null ? latest.getPublishedAt().toString() : "N/A",
                "sizeBytes", latest.getArchiveSizeBytes() != null ? latest.getArchiveSizeBytes() : 0
        );
    }

    /**
     * 获取备份的最新恢复验证结果，用于升级门禁确认隔离恢复成功。
     */
    public Map<String, Object> getLatestRestoreVerification(Long backupId) {
        List<RestoreVerification> verifications = backupMapper.listVerificationsByBackup(backupId, 1);
        if (verifications.isEmpty()) {
            return Map.of("exists", false);
        }
        RestoreVerification v = verifications.get(0);
        Map<String, Object> result = new HashMap<>();
        result.put("exists", true);
        result.put("id", v.getId());
        result.put("backupRunId", v.getBackupRunId());
        result.put("verificationKey", v.getVerificationKey());
        result.put("status", v.getStatus() != null ? v.getStatus() : "N/A");
        result.put("restoreEnvironment", v.getRestoreEnvironment() != null ? v.getRestoreEnvironment() : "N/A");
        result.put("mysqlRestored", v.getMysqlRestored() != null ? v.getMysqlRestored() : false);
        result.put("minioRestored", v.getMinioRestored() != null ? v.getMinioRestored() : false);
        result.put("qdrantRestored", v.getQdrantRestored() != null ? v.getQdrantRestored() : false);
        result.put("configRestored", v.getConfigRestored() != null ? v.getConfigRestored() : false);
        result.put("businessSampleCheck", v.getBusinessSampleCheck() != null ? v.getBusinessSampleCheck() : false);
        result.put("finishedAt", v.getFinishedAt() != null ? v.getFinishedAt().toString() : null);
        return result;
    }

    private BackupRunVO toVO(BackupRun backup) {
        BackupRunVO vo = new BackupRunVO();
        vo.setId(backup.getId());
        vo.setRunKey(backup.getRunKey());
        vo.setBackupType(backup.getBackupType());
        vo.setStatus(backup.getStatus());
        vo.setArchivePath(backup.getArchivePath());
        vo.setArchiveSizeBytes(backup.getArchiveSizeBytes());
        vo.setArchiveHash(backup.getArchiveHash());
        vo.setStartedAt(backup.getStartedAt());
        vo.setFinishedAt(backup.getFinishedAt());
        vo.setVerifiedAt(backup.getVerifiedAt());
        vo.setPublishedAt(backup.getPublishedAt());
        vo.setCreatedAt(backup.getCreatedAt());
        return vo;
    }
}
