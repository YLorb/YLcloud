package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.service.AdminMetricsService;
import com.ylcloud.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsController {
    private final AdminPermissionService permissions;
    private final AdminMetricsService metrics;
    @GetMapping("/daily")
    public Result<AdminMetricsService.Metrics> daily(@RequestParam(defaultValue="7") int days) {
        permissions.requireAdmin();
        return Result.success(metrics.daily(days));
    }
}
