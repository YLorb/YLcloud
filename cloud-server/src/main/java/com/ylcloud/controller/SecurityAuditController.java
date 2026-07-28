package com.ylcloud.controller;

import com.ylcloud.DTO.AuditQueryDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AuditRetentionConfigVO;
import com.ylcloud.VO.SecurityAuditEventVO;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.SecurityAuditService;
import com.ylcloud.context.BaseContext;
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
    private final AdminPermissionService adminPermissionService;

    /**
     * 查询审计事件。
     */
    @PostMapping("/query")
    public Result<List<SecurityAuditEventVO>> query(@RequestBody AuditQueryDTO dto) {
        adminPermissionService.requireAdmin();
        Long operatorId = BaseContext.getCurrentId();
        List<SecurityAuditEventVO> result = auditService.query(dto);
        auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                .eventType("AUDIT_ADMIN").action("QUERY").subject(operatorId, null)
                .target("AUDIT_LOG", "QUERY", null).result("SUCCESS")
                .detail(Map.of("resultCount", result.size())));
        return Result.success(result);
    }

    /**
     * 获取审计统计。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        adminPermissionService.requireAdmin();
        return Result.success(auditService.getStats());
    }

    /**
     * 获取保留配置列表。
     */
    @GetMapping("/retention")
    public Result<List<AuditRetentionConfigVO>> listRetentionConfigs() {
        adminPermissionService.requireAdmin();
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
        adminPermissionService.requireAdmin();
        Long operatorId = BaseContext.getCurrentId();
        try {
            AuditRetentionConfigVO updated = auditService.updateRetentionConfig(
                    configKey, retentionDays, permanent, description);
            auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                    .eventType("AUDIT_ADMIN").action("RETENTION_UPDATE").subject(operatorId, null)
                    .target("AUDIT_RETENTION", configKey, null).result("SUCCESS")
                    .detail(Map.of("permanent", Boolean.TRUE.equals(updated.getPermanent()),
                            "retentionDays", updated.getRetentionDays() == null ? -1 : updated.getRetentionDays())));
            return Result.success(updated);
        } catch (Exception exception) {
            auditService.recordDenied("AUDIT_ADMIN", "RETENTION_UPDATE", operatorId, null,
                    "AUDIT_RETENTION", configKey, null, exception.getMessage());
            throw exception;
        }
    }
}
