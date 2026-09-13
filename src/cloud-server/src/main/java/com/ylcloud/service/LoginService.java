package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.utils.JwtUtil;
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
    private final JwtUtil jwtUtil;
    private final SecurityAuditService auditService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginService(LoginMapper loginMapper, JwtUtil jwtUtil, SecurityAuditService auditService) {
        this.loginMapper = loginMapper;
        this.jwtUtil = jwtUtil;
        this.auditService = auditService;
    }

    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        User user = loginMapper.getByUsername(userLoginDTO.getUsername());
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
            throw new UnauthorizedException("账号已注销，请联系管理员恢复");
        }

        if(!passwordMatches(userLoginDTO.getPassword(),user)) {
            auditService.recordFailure("AUTH_LOGIN", "login", user.getId(), user.getUsername(),
                    "ACCOUNT", String.valueOf(user.getId()), user.getUsername(),
                    "密码错误", Map.of("username", userLoginDTO.getUsername()));
            throw new UnauthorizedException("用户名或密码错误");
        }

        UserLoginVO userLoginVO = new UserLoginVO();
        BeanUtils.copyProperties(user,userLoginVO);
        BaseContext.setCurrentId(user.getId());
        userLoginVO.setToken(jwtUtil.createToken(user.getUsername(),user.getId()));

        auditService.recordSuccess("AUTH_LOGIN", "login", user.getId(), user.getUsername(),
                "ACCOUNT", String.valueOf(user.getId()), user.getUsername(), Map.of());

        return userLoginVO;
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

    private boolean isBcryptHash(String password) {
        return password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$");
    }
}
