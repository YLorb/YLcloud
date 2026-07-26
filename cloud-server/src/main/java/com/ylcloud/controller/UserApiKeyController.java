package com.ylcloud.controller;

import com.ylcloud.DTO.UserApiKeyCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserApiKeyCreatedVO;
import com.ylcloud.VO.UserApiKeyVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.UserApiKeyService;
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
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class UserApiKeyController {
    private final UserApiKeyService service;

    @PostMapping
    public Result<UserApiKeyCreatedVO> create(@RequestBody @Valid UserApiKeyCreateDTO dto) {
        return Result.success(service.create(BaseContext.getCurrentId(),dto));
    }

    @GetMapping
    public Result<List<UserApiKeyVO>> list() {
        return Result.success(service.list(BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{keyId}")
    public Result<Boolean> revoke(@PathVariable Long keyId) {
        service.revoke(BaseContext.getCurrentId(),keyId);
        return Result.success(true);
    }
}
