package com.ylcloud.controller;

import com.ylcloud.DTO.AdminPermissionGroupDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.PermissionDefinitionVO;
import com.ylcloud.VO.PermissionGroupVO;
import com.ylcloud.service.AccessControlService;
import com.ylcloud.service.AdminPermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/permission-groups")
@RequiredArgsConstructor
public class AdminPermissionGroupController {
    private final AdminPermissionService adminPermissionService;
    private final AccessControlService accessControlService;

    @GetMapping("/definitions")
    public Result<List<PermissionDefinitionVO>> definitions() {
        adminPermissionService.requireAdmin();
        return Result.success(accessControlService.definitions());
    }

    @GetMapping
    public Result<List<PermissionGroupVO>> list() {
        adminPermissionService.requireAdmin();
        return Result.success(accessControlService.listGroups());
    }

    @PostMapping
    public Result<PermissionGroupVO> create(@RequestBody @Valid AdminPermissionGroupDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(accessControlService.createGroup(dto));
    }

    @PutMapping("/{groupId}")
    public Result<PermissionGroupVO> update(@PathVariable Long groupId, @RequestBody @Valid AdminPermissionGroupDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(accessControlService.updateGroup(groupId,dto));
    }

    @DeleteMapping("/{groupId}")
    public Result<Boolean> delete(@PathVariable Long groupId) {
        adminPermissionService.requireAdmin();
        accessControlService.deleteGroup(groupId);
        return Result.success(true);
    }
}
