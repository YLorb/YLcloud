package com.ylcloud.controller;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.Result;
import com.ylcloud.service.SignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class Sign {

    private final SignService signService;

    public Sign(SignService signService) {
        this.signService = signService;
    }

    @PostMapping("/sign")
    public Result sign(@RequestBody UserRegisterDTO userRegisterDTO) {
        log.info("用户尝试注册：{}", userRegisterDTO);
        signService.signup(userRegisterDTO);
        return Result.success();
    }
}
