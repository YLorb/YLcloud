package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminPermissionService {
    public static final String ROLE_ADMIN = "ADMIN";

    private final LoginMapper loginMapper;
    private SecurityAuditService auditService;

    @Autowired(required = false)
    public void setAuditService(SecurityAuditService auditService) {
        this.auditService = auditService;
    }

    public void requireAdmin() {
        Long userId = BaseContext.getCurrentId();
        if(userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        User user = loginMapper.getById(userId);
        if(user == null || user.getRole() == null || !ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            throw new ForbiddenException("无管理员权限");
        }
    }

    public void requireDeploymentOwner() {
        Long userId = BaseContext.getCurrentId();
        if(userId == null) {
            if(auditService != null) auditService.recordDenied("DEPLOYMENT_OWNER", "ACCESS", null, null,
                    "DEPLOYMENT", "CURRENT", null, "请先登录");
            throw new UnauthorizedException("请先登录");
        }
        User user = loginMapper.getById(userId);
        if(user == null || !Boolean.TRUE.equals(user.getDeploymentOwner())) {
            if(auditService != null) auditService.recordDenied("DEPLOYMENT_OWNER", "ACCESS", userId, null,
                    "DEPLOYMENT", "CURRENT", null, "仅部署所有者可以执行此操作");
            throw new ForbiddenException("仅部署所有者可以执行此操作");
        }
        if(auditService != null) auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                .eventType("DEPLOYMENT_OWNER").action("ACCESS").subject(userId, user.getUsername())
                .target("DEPLOYMENT", "CURRENT", null).result("SUCCESS"));
    }
}
