package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminPermissionService {
    public static final String ROLE_ADMIN = "ADMIN";

    private final LoginMapper loginMapper;

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
}
