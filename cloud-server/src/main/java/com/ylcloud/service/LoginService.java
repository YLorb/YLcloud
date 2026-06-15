package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Exception.BaseException;
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

@Service
@Slf4j
public class LoginService {
    private final LoginMapper loginMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginService(LoginMapper loginMapper, JwtUtil jwtUtil) {
        this.loginMapper = loginMapper;
        this.jwtUtil = jwtUtil;
    }

    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        User user = loginMapper.getByUsername(userLoginDTO.getUsername());
        if(user == null || !StatusConstant.ENABLE.equals(user.getStatus())) {
            throw new BaseException("用户不存在或已被禁用");
        }
        if(!passwordMatches(userLoginDTO.getPassword(),user)) {
            throw new BaseException("密码错误");
        }

        UserLoginVO userLoginVO = new UserLoginVO();
        BeanUtils.copyProperties(user,userLoginVO);
        BaseContext.setCurrentId(user.getId());
        userLoginVO.setToken(jwtUtil.createToken(user.getUsername(),user.getId()));
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
