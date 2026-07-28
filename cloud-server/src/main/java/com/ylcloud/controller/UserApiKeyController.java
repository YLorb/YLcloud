package com.ylcloud.controller;

import com.ylcloud.DTO.UserApiKeyCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserApiKeyCreatedVO;
import com.ylcloud.VO.UserApiKeyVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SecurityAuditService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class UserApiKeyController {
    private final UserApiKeyService service;
    private final SecurityAuditService auditService;

    @PostMapping
    public Result<UserApiKeyCreatedVO> create(@RequestBody @Valid UserApiKeyCreateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        try {
            UserApiKeyCreatedVO result = service.create(userId, dto);
            auditService.recordSuccess("API_KEY", "CREATE", userId, null,
                    "API_KEY", result.getApiKey() != null && result.getApiKey().getId() != null ? result.getApiKey().getId().toString() : null, dto.getName(),
                    Map.of("driveAccess", dto.getDriveAccess() != null ? dto.getDriveAccess() : "NONE"));
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("API_KEY", "CREATE", userId, null,
                    "API_KEY", null, dto.getName(), e.getMessage(), Map.of());
            throw e;
        }
    }

    @GetMapping
    public Result<List<UserApiKeyVO>> list() {
        return Result.success(service.list(BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{keyId}")
    public Result<Boolean> revoke(@PathVariable Long keyId) {
        Long userId = BaseContext.getCurrentId();
        try {
            service.revoke(userId, keyId);
            auditService.recordSuccess("API_KEY", "REVOKE", userId, null,
                    "API_KEY", keyId.toString(), null, Map.of());
            return Result.success(true);
        } catch (Exception e) {
            auditService.recordFailure("API_KEY", "REVOKE", userId, null,
                    "API_KEY", keyId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }
}
