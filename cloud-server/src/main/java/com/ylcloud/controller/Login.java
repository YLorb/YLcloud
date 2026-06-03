package com.ylcloud.controller;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.service.LoginService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class Login {

    @Autowired
    private LoginService loginService;

    /**
     * 用户登录并返回登录态信息。
     *
     * @param userLoginDTO 登录参数
     * @return 登录用户信息和令牌
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO) {
        log.info("用户尝试登录：{}", userLoginDTO);
        UserLoginVO user = loginService.login(userLoginDTO);
        log.info("result:用户id：{}，token内部id：{}",user.getId(),user.getToken());
        return Result.success(user);
    }
}
