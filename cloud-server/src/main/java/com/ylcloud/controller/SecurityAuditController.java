package com.ylcloud.controller;

import com.ylcloud.DTO.AuditQueryDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AuditRetentionConfigVO;
import com.ylcloud.VO.SecurityAuditEventVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TASK-012: 统一安全审计控制器。
 * 提供审计查询和保留配置管理 API。
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class SecurityAuditController {
    private final SecurityAuditService auditService;

    /**
     * 查询审计事件。
     */
    @PostMapping("/query")
    public Result<List<SecurityAuditEventVO>> query(@RequestBody AuditQueryDTO dto) {
        return Result.success(auditService.query(dto));
    }

    /**
     * 获取审计统计。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(auditService.getStats());
    }

    /**
     * 获取保留配置列表。
     */
    @GetMapping("/retention")
    public Result<List<AuditRetentionConfigVO>> listRetentionConfigs() {
        return Result.success(auditService.listRetentionConfigs());
    }

    /**
     * 更新保留配置。
     */
    @PutMapping("/retention/{configKey}")
    public Result<AuditRetentionConfigVO> updateRetentionConfig(
            @PathVariable String configKey,
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Boolean permanent,
            @RequestParam(required = false) String description) {
        return Result.success(auditService.updateRetentionConfig(configKey, retentionDays, permanent, description));
    }
}
