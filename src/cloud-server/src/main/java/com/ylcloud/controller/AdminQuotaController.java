package com.ylcloud.controller;

import com.ylcloud.DTO.QuotaPolicyUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.entity.QuotaPolicy;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.QuotaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quota")
@RequiredArgsConstructor
public class AdminQuotaController {
    private final AdminPermissionService permissions;
    private final QuotaService quotas;

    @GetMapping("/groups/{groupId}")
    public Result<QuotaPolicy> group(@PathVariable Long groupId) {
        permissions.requireAdmin();
        return Result.success(quotas.groupPolicy(groupId));
    }

    @PutMapping("/groups/{groupId}")
    public Result<QuotaPolicy> update(@PathVariable Long groupId,@RequestBody @Valid QuotaPolicyUpdateDTO dto) {
        permissions.requireAdmin();
        return Result.success(quotas.updateGroupPolicy(groupId,dto));
    }

    @PutMapping("/reconcile")
    public Result<Boolean> reconcile() {
        permissions.requireAdmin();
        quotas.reconcile();
        return Result.success(true);
    }
}
