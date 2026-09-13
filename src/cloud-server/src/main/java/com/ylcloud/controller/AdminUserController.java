package com.ylcloud.controller;

import com.ylcloud.DTO.AdminUserAccessUpdateDTO;
import com.ylcloud.DTO.AdminUserCreateDTO;
import com.ylcloud.DTO.AdminTestAccountPurgeDTO;
import com.ylcloud.DTO.AdminUserUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AccountStatusVO;
import com.ylcloud.VO.AdminUserVO;
import com.ylcloud.service.AccountLifecycleService;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.AdminUserService;
import com.ylcloud.service.SecurityAuditService;
import com.ylcloud.context.BaseContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminPermissionService adminPermissionService;
    private final AdminUserService adminUserService;
    private final AccountLifecycleService accountLifecycleService;
    private final SecurityAuditService auditService;

    @GetMapping
    public Result<List<AdminUserVO>> list() {
        adminPermissionService.requireAdmin();
        return Result.success(adminUserService.list());
    }

    @GetMapping("/{userId}/account-status")
    public Result<AccountStatusVO> accountStatus(@PathVariable Long userId) {
        adminPermissionService.requireAdmin();
        return Result.success(accountLifecycleService.getStatus(userId));
    }

    @PostMapping
    public Result<AdminUserVO> create(@RequestBody @Valid AdminUserCreateDTO dto) {
        adminPermissionService.requireAdmin();
        Long adminId = BaseContext.getCurrentId();
        try {
            AdminUserVO result = adminUserService.create(dto);
            auditService.recordSuccess("ADMIN_USER", "CREATE", adminId, null,
                    "USER", result.getId() != null ? result.getId().toString() : null, dto.getUsername(),
                    Map.of("role", dto.getRole() != null ? dto.getRole() : "ADMIN"));
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("ADMIN_USER", "CREATE", adminId, null,
                    "USER", null, dto.getUsername(), e.getMessage(), Map.of());
            throw e;
        }
    }

    @PutMapping("/{userId}")
    public Result<AdminUserVO> update(@PathVariable Long userId, @RequestBody AdminUserUpdateDTO dto) {
        adminPermissionService.requireAdmin();
        Long adminId = BaseContext.getCurrentId();
        try {
            AdminUserVO result = adminUserService.update(userId, dto);
            auditService.recordSuccess("ADMIN_USER", "UPDATE", adminId, null,
                    "USER", userId.toString(), null, Map.of());
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("ADMIN_USER", "UPDATE", adminId, null,
                    "USER", userId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }


    @PutMapping("/{userId}/access")
    public Result<AdminUserVO> updateAccess(@PathVariable Long userId, @RequestBody AdminUserAccessUpdateDTO dto) {
        adminPermissionService.requireAdmin();
        Long adminId = BaseContext.getCurrentId();
        try {
            AdminUserVO result = adminUserService.updateAccess(userId, dto);
            auditService.recordSuccess("ADMIN_USER", "UPDATE_ACCESS", adminId, null,
                    "USER", userId.toString(), null, Map.of());
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("ADMIN_USER", "UPDATE_ACCESS", adminId, null,
                    "USER", userId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }

    /**
     * 仅用于显式开启的 E2E 环境。目标必须是已注销、无 TEAM 所有权的课程测试账号。
     */
    @PostMapping("/{userId}/purge-test-account")
    public Result<AccountStatusVO> purgeTestAccount(@PathVariable Long userId,
                                                     @RequestBody @Valid AdminTestAccountPurgeDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(accountLifecycleService.forcePurgeTestAccount(
                BaseContext.getCurrentId(), userId, dto.getExpectedUsername()));
    }
}
