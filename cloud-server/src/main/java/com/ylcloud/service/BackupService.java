package com.ylcloud.service;

import com.ylcloud.VO.BackupRunVO;
import com.ylcloud.entity.BackupRun;
import com.ylcloud.mapper.BackupMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
                                               String archiveHash, String manifestJson) {
        BackupRun existing = backupMapper.getBackupByKey(runKey);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setStatus("READY");
            existing.setArchivePath(archivePath);
            existing.setArchiveSizeBytes(archiveSize);
            existing.setArchiveHash(archiveHash);
            existing.setManifestJson(manifestJson);
            existing.setVerifiedAt(now);
            existing.setPublishedAt(now);
            existing.setUpdatedAt(now);
            backupMapper.updateBackup(existing);
            return toVO(existing);
        }

        BackupRun backup = new BackupRun();
        backup.setRunKey(runKey);
        backup.setBackupType("FULL");
        backup.setStatus("READY");
        backup.setArchivePath(archivePath);
        backup.setArchiveSizeBytes(archiveSize);
        backup.setArchiveHash(archiveHash);
        backup.setManifestJson(manifestJson);
        backup.setStartedAt(now);
        backup.setFinishedAt(now);
        backup.setVerifiedAt(now);
        backup.setPublishedAt(now);
        backup.setCreatedAt(now);
        backup.setUpdatedAt(now);
        backupMapper.insertBackup(backup);

        // 轮换旧备份
        rotateOldBackups();

        return toVO(backup);
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
