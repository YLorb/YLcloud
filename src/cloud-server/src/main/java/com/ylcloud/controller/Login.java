package com.ylcloud.controller;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.service.LoginService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class Login {
    private final LoginService loginService;

    public Login(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO) {
        log.info("login attempt username={}",userLoginDTO.getUsername());
        UserLoginVO user = loginService.login(userLoginDTO);
        log.info("login success userId={}",user.getId());
        return Result.success(user);
    }
}
