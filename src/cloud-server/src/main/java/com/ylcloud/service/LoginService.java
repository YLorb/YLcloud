package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class LoginService {
    private final LoginMapper loginMapper;
    private final BrowserSessionService sessions;
    private final SecurityAuditService auditService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginService(LoginMapper loginMapper, BrowserSessionService sessions, SecurityAuditService auditService) {
        this.loginMapper = loginMapper;
        this.sessions = sessions;
        this.auditService = auditService;
    }

    public record LoginResult(UserLoginVO user, String credential) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
            noRollbackFor = UnauthorizedException.class)
    public LoginResult login(UserLoginDTO userLoginDTO, HttpServletRequest request) {
        User user = loginMapper.getByUsername(userLoginDTO.getUsername());
        if (user != null) {
            loginMapper.lockUserId(user.getId());
            user = loginMapper.getByUsername(userLoginDTO.getUsername());
        }
        if(user == null || !StatusConstant.ENABLE.equals(user.getStatus())) {
            auditService.recordFailure("AUTH_LOGIN", "login", null, userLoginDTO.getUsername(),
                    "ACCOUNT", null, null, "用户名或密码错误", Map.of("username", userLoginDTO.getUsername()));
            throw new UnauthorizedException("用户名或密码错误");
        }

        // TASK-010: 检查账号生命周期状态
        String accountStatus = user.getAccountStatus();
        if(accountStatus != null && !"ACTIVE".equals(accountStatus)) {
            auditService.recordDenied("AUTH_LOGIN", "login", user.getId(), user.getUsername(),
                    "ACCOUNT", String.valueOf(user.getId()), user.getUsername(),
                    "账号已注销: " + accountStatus);
            throw new UnauthorizedException("用户名或密码错误");
        }

        if(!passwordMatches(userLoginDTO.getPassword(),user)) {
            auditService.recordFailure("AUTH_LOGIN", "login", user.getId(), user.getUsername(),
                    "ACCOUNT", String.valueOf(user.getId()), user.getUsername(),
                    "密码错误", Map.of("username", userLoginDTO.getUsername()));
            throw new UnauthorizedException("用户名或密码错误");
        }

        UserLoginVO userLoginVO = new UserLoginVO();
        BeanUtils.copyProperties(user,userLoginVO);
        String credential = sessions.create(user.getId(), request);

        auditService.recordSuccess("AUTH_LOGIN", "login", user.getId(), user.getUsername(),
                "ACCOUNT", String.valueOf(user.getId()), user.getUsername(), Map.of());

        return new LoginResult(userLoginVO, credential);
    }

    private boolean passwordMatches(String rawPassword, User user) {
        String storedPassword = user.getPassword();
        if(storedPassword == null || storedPassword.isBlank()) {
            return false;
        }
        if(isBcryptHash(storedPassword)) {
            return passwordEncoder.matches(rawPassword,storedPassword);
        }
        boolean matched = rawPassword.equals(storedPassword);
        if(matched) {
            loginMapper.updatePassword(user.getId(),passwordEncoder.encode(rawPassword));
            log.info("legacy password upgraded userId={}",user.getId());
        }
        return matched;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        loginMapper.lockUserId(userId);
        User user = loginMapper.getById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) ||
                (user.getAccountStatus() != null && !"ACTIVE".equals(user.getAccountStatus())) ||
                !passwordMatches(currentPassword, user)) {
            throw new com.ylcloud.Exception.ForbiddenException("当前密码不正确或账号不可用");
        }
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new com.ylcloud.Exception.ForbiddenException("新密码的 UTF-8 长度不能超过 72 字节");
        }
        loginMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        sessions.revokeAll(userId);
        auditService.recordSuccess("AUTH_PASSWORD_CHANGE", "changePassword", userId, user.getUsername(),
                "ACCOUNT", String.valueOf(userId), null, Map.of());
    }

    private boolean isBcryptHash(String password) {
        return password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$");
    }
}
