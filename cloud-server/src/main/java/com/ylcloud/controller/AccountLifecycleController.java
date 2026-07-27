package com.ylcloud.controller;

import com.ylcloud.DTO.AccountCancelDTO;
import com.ylcloud.DTO.AccountRecoverDTO;
import com.ylcloud.DTO.DataExportRequestDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AccountStatusVO;
import com.ylcloud.VO.DataExportJobVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AccountLifecycleService;
import com.ylcloud.service.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        return Result.success(exportService.requestExport(userId, scope));
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
        return Result.success(exportService.getExport(userId, jobId));
    }
}
