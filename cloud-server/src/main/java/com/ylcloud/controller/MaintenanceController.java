package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.MaintenanceModeService;
import com.ylcloud.service.SecurityAuditService;
import com.ylcloud.context.BaseContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * TASK-014: 维护模式控制器。
 * 提供维护模式启用/禁用和状态查询 API。
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {
    private final MaintenanceModeService maintenanceService;
    private final AdminPermissionService adminPermissionService;
    private final SecurityAuditService auditService;

    /**
     * 启用维护模式。
     */
    @PostMapping("/enable")
    public Result<Map<String, Object>> enable(@RequestParam(required = false) String reason) {
        adminPermissionService.requireAdmin();
        Long operatorId = BaseContext.getCurrentId();
        try {
            maintenanceService.enableMaintenanceMode(reason);
            auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                    .eventType("DEPLOYMENT").action("MAINTENANCE_ENABLE").subject(operatorId, null)
                    .target("DEPLOYMENT", "CURRENT", null).result("SUCCESS")
                    .detail(Map.of("reason", reason == null ? "" : reason)));
        } catch (Exception exception) {
            auditService.recordFailure("DEPLOYMENT", "MAINTENANCE_ENABLE", operatorId, null,
                    "DEPLOYMENT", "CURRENT", null, exception.getMessage(), Map.of());
            throw exception;
        }
        return Result.success(Map.of(
                "status", "enabled",
                "message", "Maintenance mode enabled"
        ));
    }

    /**
     * 禁用维护模式。
     */
    @PostMapping("/disable")
    public Result<Map<String, Object>> disable() {
        adminPermissionService.requireAdmin();
        Long operatorId = BaseContext.getCurrentId();
        try {
            maintenanceService.disableMaintenanceMode();
            auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                    .eventType("DEPLOYMENT").action("MAINTENANCE_DISABLE").subject(operatorId, null)
                    .target("DEPLOYMENT", "CURRENT", null).result("SUCCESS"));
        } catch (Exception exception) {
            auditService.recordFailure("DEPLOYMENT", "MAINTENANCE_DISABLE", operatorId, null,
                    "DEPLOYMENT", "CURRENT", null, exception.getMessage(), Map.of());
            throw exception;
        }
        return Result.success(Map.of(
                "status", "disabled",
                "message", "Maintenance mode disabled"
        ));
    }

    /**
     * 获取维护模式状态。
     */
    @GetMapping("/status")
    public Result<MaintenanceModeService.MaintenanceStatus> status() {
        adminPermissionService.requireAdmin();
        return Result.success(maintenanceService.getStatus());
    }
}
