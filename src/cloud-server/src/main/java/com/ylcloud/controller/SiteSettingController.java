package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.PublicSiteSettingVO;
import com.ylcloud.service.SiteSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/site")
@RequiredArgsConstructor
public class SiteSettingController {
    private final SiteSettingService siteSettingService;

    @GetMapping("/public-settings")
    public Result<PublicSiteSettingVO> publicSettings() {
        return Result.success(siteSettingService.publicSettings());
    }
}
