package com.ylcloud.controller;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.Result;
import com.ylcloud.service.SignService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class Sign {

    private final SignService signService;

    /**
     * 创建注册控制器。
     *
     * @param signService 注册业务服务
     */
    public Sign(SignService signService) {
        this.signService = signService;
    }

    /**
     * 注册新用户并初始化用户根目录。
     *
     * @param userRegisterDTO 注册参数
     * @return 通用成功响应
     */
    @PostMapping("/sign")
    public Result sign(@RequestBody @Valid UserRegisterDTO userRegisterDTO) {
        log.info("用户尝试注册：{}", userRegisterDTO);
        signService.signup(userRegisterDTO);
        return Result.success();
    }
}
