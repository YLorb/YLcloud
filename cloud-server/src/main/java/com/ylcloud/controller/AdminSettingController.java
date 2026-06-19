package com.ylcloud.controller;

import com.ylcloud.DTO.SiteSettingUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.SiteSettingVO;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.SiteSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingController {
    private final SiteSettingService siteSettingService;
    private final AdminPermissionService adminPermissionService;

    @GetMapping
    public Result<List<SiteSettingVO>> list() {
        adminPermissionService.requireAdmin();
        return Result.success(siteSettingService.listForAdmin());
    }

    @PutMapping
    public Result<Void> update(@RequestBody @Valid SiteSettingUpdateDTO dto) {
        adminPermissionService.requireAdmin();
        siteSettingService.updateBatch(dto);
        return Result.success();
    }
}
