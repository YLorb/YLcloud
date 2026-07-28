package com.ylcloud.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

/**
 * TASK-014: 维护模式服务。
 * 维护模式拒绝写入和新的 Agent/异步任务，允许只读和健康接口。
 */
@Service
@Slf4j
public class MaintenanceModeService {
    private static final String MAINTENANCE_MARKER = "maintenance-mode";
    private final Path markerPath;

    private volatile boolean maintenanceMode = false;
    private volatile LocalDateTime maintenanceStartedAt = null;
    private volatile String maintenanceReason = null;

    public MaintenanceModeService(
            @Value("${ylcloud.deploy.state-root:${YLCLOUD_DEPLOY_STATE_ROOT:/var/lib/ylcloud-deploy}}")
            String stateRoot) {
        this.markerPath = Paths.get(stateRoot, MAINTENANCE_MARKER);
    }

    /**
     * 启用维护模式。
     */
    public void enableMaintenanceMode(String reason) {
        try {
            Files.createDirectories(markerPath.getParent());
            Files.writeString(markerPath, reason != null ? reason : "Maintenance mode enabled");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist maintenance marker", e);
        }
        maintenanceMode = true;
        maintenanceStartedAt = LocalDateTime.now();
        maintenanceReason = reason;
        log.info("Maintenance mode enabled: {}", reason);
    }

    /**
     * 禁用维护模式。
     */
    public void disableMaintenanceMode() {
        try {
            Files.deleteIfExists(markerPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to remove maintenance marker", e);
        }
        maintenanceMode = false;
        maintenanceStartedAt = null;
        maintenanceReason = null;
        log.info("Maintenance mode disabled");
    }

    /**
     * 检查是否处于维护模式。
     */
    public boolean isMaintenanceMode() {
        // Check in-memory flag first
        if (maintenanceMode) {
            return true;
        }

        // Check marker file as fallback
        try {
            return Files.exists(markerPath);
        } catch (Exception e) {
            log.error("Failed to inspect maintenance marker; keeping writes fenced", e);
            return true;
        }
    }

    /**
     * 获取维护模式状态。
     */
    public MaintenanceStatus getStatus() {
        return new MaintenanceStatus(
                isMaintenanceMode(),
                maintenanceStartedAt,
                maintenanceReason
        );
    }

    /**
     * 检查请求是否在维护模式下允许。
     * 允许：健康检查、只读查询、恢复操作
     * 拒绝：写入操作、新 Agent/异步任务
     */
    public boolean isRequestAllowed(String method, String path) {
        if (!isMaintenanceMode()) {
            return true;
        }

        // Always allow health endpoints
        if (path.contains("/actuator/health") || path.contains("/api/site/public-settings")) {
            return true;
        }

        // Allow GET requests (read-only)
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }

        // Allow maintenance disable endpoint
        if (path.contains("/api/admin/maintenance/disable")) {
            return true;
        }

        // Reject all other write operations
        return false;
    }

    public record MaintenanceStatus(boolean active, LocalDateTime startedAt, String reason) {}
}
