package com.ylcloud.controller;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.service.LoginService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class Login {

    @Autowired
    private LoginService loginService;

    /*public Login(LoginService loginService) {
        this.loginService = loginService;
    }*/

    @PostMapping("/login")
    //@Valid：告诉程序此处需要校验（校验器是自己写的，在DTO中通过注解实现了）
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO) {
        log.info("用户尝试登录：{}", userLoginDTO); // TODO:密码变更为加密存储
        UserLoginVO user = loginService.login(userLoginDTO);
        return Result.success(user);
    }
}
