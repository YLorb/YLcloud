package com.ylcloud.controller;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Result;
import com.ylcloud.service.LoginService;
import com.ylcloud.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class Login {

    private final LoginService loginService;

    public Login(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public Result<User> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("用户尝试登录：{}", userLoginDTO);
        User user = loginService.login(userLoginDTO);
        return Result.success(user);
    }
}
