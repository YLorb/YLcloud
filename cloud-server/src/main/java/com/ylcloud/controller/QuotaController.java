package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.QuotaUsageVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.QuotaService;
import com.ylcloud.service.SpacePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quota")
@RequiredArgsConstructor
public class QuotaController {
    private final QuotaService quotas;
    private final SpacePermissionService spaces;

    @GetMapping("/usage")
    public Result<QuotaUsageVO> mine() { return Result.success(quotas.userUsage(BaseContext.getCurrentId())); }

    @GetMapping("/teams/{spaceId}")
    public Result<QuotaUsageVO> team(@PathVariable Long spaceId) {
        spaces.requireMember(spaceId,BaseContext.getCurrentId());
        return Result.success(quotas.teamUsage(spaceId));
    }
}
