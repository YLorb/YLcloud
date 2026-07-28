package com.ylcloud.controller;

import com.ylcloud.DTO.AccountCancelDTO;
import com.ylcloud.DTO.AccountRecoverDTO;
import com.ylcloud.DTO.DataExportRequestDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AccountStatusVO;
import com.ylcloud.VO.DataExportJobVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AccountLifecycleService;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.DataExportService;
import com.ylcloud.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TASK-010: 账号生命周期控制器。
 * 提供注销、恢复、状态查询和数据导出 API。
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountLifecycleController {
    private final AccountLifecycleService lifecycleService;
    private final DataExportService exportService;
    private final AdminPermissionService adminPermissionService;
    private final SecurityAuditService auditService;

    /**
     * 获取当前用户账号状态。
     */
    @GetMapping("/status")
    public Result<AccountStatusVO> getStatus() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(lifecycleService.getStatus(userId));
    }

    /**
     * 请求注销账号。
     */
    @PostMapping("/cancel")
    public Result<AccountStatusVO> requestCancellation(@RequestBody(required = false) AccountCancelDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (dto == null) dto = new AccountCancelDTO();
        return Result.success(lifecycleService.requestCancellation(userId, dto));
    }

    /**
     * ADMIN 恢复已注销账号。
     */
    @PostMapping("/recover")
    public Result<AccountStatusVO> recoverAccount(@RequestBody AccountRecoverDTO dto) {
        adminPermissionService.requireAdmin();
        Long adminId = BaseContext.getCurrentId();
        return Result.success(lifecycleService.recoverAccount(adminId, dto));
    }

    /**
     * 请求数据导出。
     */
    @PostMapping("/export")
    public Result<DataExportJobVO> requestExport(@RequestBody(required = false) DataExportRequestDTO dto) {
        Long userId = BaseContext.getCurrentId();
        String scope = dto != null ? dto.getExportScope() : null;
        try {
            DataExportJobVO job = exportService.requestExport(userId, scope);
            auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                    .eventType("DATA_EXPORT").action("REQUEST").subject(userId, null)
                    .target("EXPORT_JOB", String.valueOf(job.getId()), null).result("SUCCESS")
                    .detail(Map.of("scope", scope == null ? "FULL" : scope)));
            return Result.success(job);
        } catch (Exception exception) {
            auditService.recordFailure("DATA_EXPORT", "REQUEST", userId, null,
                    "ACCOUNT", String.valueOf(userId), null, exception.getMessage(),
                    Map.of("scope", scope == null ? "FULL" : scope));
            throw exception;
        }
    }

    /**
     * 获取导出任务列表。
     */
    @GetMapping("/export/list")
    public Result<List<DataExportJobVO>> listExports(@RequestParam(defaultValue = "20") int limit) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(exportService.listExports(userId, limit));
    }

    /**
     * 获取单个导出任务详情。
     */
    @GetMapping("/export/{jobId}")
    public Result<DataExportJobVO> getExport(@PathVariable Long jobId) {
        Long userId = BaseContext.getCurrentId();
        try {
            DataExportJobVO export = exportService.getExport(userId, jobId);
            auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                    .eventType("DATA_EXPORT").action("ACCESS_DOWNLOAD_CREDENTIAL").subject(userId, null)
                    .target("EXPORT_JOB", jobId.toString(), null).result("SUCCESS"));
            return Result.success(export);
        } catch (Exception exception) {
            auditService.recordDenied("DATA_EXPORT", "ACCESS_DOWNLOAD_CREDENTIAL", userId, null,
                    "EXPORT_JOB", jobId.toString(), null, exception.getMessage());
            throw exception;
        }
    }
}
