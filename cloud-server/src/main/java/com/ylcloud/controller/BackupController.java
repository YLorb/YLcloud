package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.BackupRunVO;
import com.ylcloud.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TASK-013: 备份管理控制器。
 * 提供备份状态查询 API。
 */
@RestController
@RequestMapping("/api/admin/backup")
@RequiredArgsConstructor
public class BackupController {
    private final BackupService backupService;

    /**
     * 获取 READY 备份列表。
     */
    @GetMapping("/ready")
    public Result<List<BackupRunVO>> listReadyBackups(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(backupService.listReadyBackups(limit));
    }

    /**
     * 获取最近备份列表。
     */
    @GetMapping("/recent")
    public Result<List<BackupRunVO>> listRecentBackups(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(backupService.listRecentBackups(limit));
    }

    /**
     * 获取备份详情。
     */
    @GetMapping("/{id}")
    public Result<BackupRunVO> getBackup(@PathVariable Long id) {
        BackupRunVO backup = backupService.getBackup(id);
        if (backup == null) {
            return Result.error("备份不存在");
        }
        return Result.success(backup);
    }

    /**
     * 获取备份统计。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(backupService.getStats());
    }

    /**
     * 记录备份完成（由外部脚本调用）。
     */
    @PostMapping("/record")
    public Result<BackupRunVO> recordBackup(
            @RequestParam String runKey,
            @RequestParam String archivePath,
            @RequestParam Long archiveSize,
            @RequestParam String archiveHash,
            @RequestParam(required = false) String manifestJson) {
        return Result.success(backupService.recordBackupCompletion(
                runKey, archivePath, archiveSize, archiveHash, manifestJson));
    }
}
