package com.ylcloud.controller;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.service.LoginService;
import com.ylcloud.service.BrowserSessionService;
import com.ylcloud.service.LoginRateLimiter;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.SecurityAuditService;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class Login {
    private final LoginService loginService;
    private final BrowserSessionService sessions;
    private final LoginRateLimiter limiter;
    private final AdminPermissionService admin;
    private final SecurityAuditService audit;

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO dto,
                                    HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!limiter.allow(dto.getUsername(), request.getRemoteAddr())) {
            response.setStatus(429);
            response.setHeader("Retry-After", "900");
            return Result.error(429, "登录尝试过于频繁，请稍后重试");
        }
        var result = loginService.login(dto, request);
        sessions.setCookie(response, result.credential(), dto.isRememberMe());
        return Result.success(result.user());
    }

    @GetMapping("/session")
    public Result<UserLoginVO> current(HttpServletRequest request) {
        var view = new UserLoginVO();
        BeanUtils.copyProperties((User) request.getAttribute(BrowserSessionService.USER_ATTRIBUTE), view);
        return Result.success(view);
    }

    @PostMapping("/logout")
    public Result<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revokeCurrent(request);
        sessions.clearCookie(response);
        record("AUTH_LOGOUT", BaseContext.getCurrentId());
        return Result.success(true);
    }

    @PostMapping("/logout-all")
    public Result<Boolean> logoutAll(HttpServletResponse response) {
        sessions.revokeAll(BaseContext.getCurrentId());
        sessions.clearCookie(response);
        record("AUTH_REVOKE_ALL", BaseContext.getCurrentId());
        return Result.success(true);
    }

    public record PasswordChange(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=128) String currentPassword,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=8,max=72) String newPassword) {}

    @PostMapping("/session/password")
    public Result<Boolean> password(@RequestBody @Valid PasswordChange body,
                                   HttpServletRequest request, HttpServletResponse response) {
        if (!limiter.allow("password-change:" + BaseContext.getCurrentId(), request.getRemoteAddr())) {
            response.setStatus(429);
            response.setHeader("Retry-After", "900");
            return Result.error(429, "操作过于频繁，请稍后重试");
        }
        loginService.changePassword(BaseContext.getCurrentId(), body.currentPassword(), body.newPassword());
        sessions.clearCookie(response);
        return Result.success(true);
    }

    @PostMapping("/admin/users/{userId}/sessions/revoke")
    public Result<Boolean> forceLogout(@PathVariable Long userId) {
        admin.requireAdmin();
        sessions.revokeAll(userId);
        record("AUTH_FORCE_LOGOUT", userId);
        return Result.success(true);
    }

    private void record(String event, Long target) {
        audit.recordSuccess(event, "revoke", BaseContext.getCurrentId(), null,
                "ACCOUNT", String.valueOf(target), null, Map.of());
    }
}
