package com.ylcloud.controller;

import com.ylcloud.DTO.AgentRiskAuthorizationCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AgentRiskAuthorizationVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AgentRiskAuthorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-risk-authorizations")
@RequiredArgsConstructor
public class AgentRiskAuthorizationController {
    private final AgentRiskAuthorizationService service;

    @PostMapping
    public Result<AgentRiskAuthorizationVO> issue(@RequestBody @Valid AgentRiskAuthorizationCreateDTO dto) {
        return Result.success(service.issueForWeb(BaseContext.getCurrentId(),dto));
    }

    @GetMapping
    public Result<List<AgentRiskAuthorizationVO>> list() {
        return Result.success(service.list(BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{authorizationId}")
    public Result<Boolean> revoke(@PathVariable Long authorizationId) {
        service.revoke(BaseContext.getCurrentId(),authorizationId);
        return Result.success(true);
    }
}
