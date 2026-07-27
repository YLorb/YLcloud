package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.service.MaintenanceModeService;
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

    /**
     * 启用维护模式。
     */
    @PostMapping("/enable")
    public Result<Map<String, Object>> enable(@RequestParam(required = false) String reason) {
        maintenanceService.enableMaintenanceMode(reason);
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
        maintenanceService.disableMaintenanceMode();
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
        return Result.success(maintenanceService.getStatus());
    }
}
