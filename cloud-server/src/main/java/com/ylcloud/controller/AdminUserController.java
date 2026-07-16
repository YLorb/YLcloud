package com.ylcloud.controller;

import com.ylcloud.DTO.AdminUserAccessUpdateDTO;
import com.ylcloud.DTO.AdminUserCreateDTO;
import com.ylcloud.DTO.AdminUserUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AdminUserVO;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminPermissionService adminPermissionService;
    private final AdminUserService adminUserService;

    @GetMapping
    public Result<List<AdminUserVO>> list() {
        adminPermissionService.requireAdmin();
        return Result.success(adminUserService.list());
    }

    @PostMapping
    public Result<AdminUserVO> create(@RequestBody @Valid AdminUserCreateDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(adminUserService.create(dto));
    }

    @PutMapping("/{userId}")
    public Result<AdminUserVO> update(@PathVariable Long userId, @RequestBody AdminUserUpdateDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(adminUserService.update(userId,dto));
    }


    @PutMapping("/{userId}/access")
    public Result<AdminUserVO> updateAccess(@PathVariable Long userId, @RequestBody AdminUserAccessUpdateDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(adminUserService.updateAccess(userId,dto));
    }
}
