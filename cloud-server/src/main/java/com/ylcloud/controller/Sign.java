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
     * 初始化 Sign 对象。
     *
     * @param signService 注册服务
     */
    public Sign(SignService signService) {
        this.signService = signService;
    }

    /**
     * 执行 sign 函数的业务处理。
     *
     * @param userRegisterDTO 注册参数
     * @return 处理结果
     */
    @PostMapping("/sign")
    public Result sign(@RequestBody @Valid UserRegisterDTO userRegisterDTO) {
        log.info("用户尝试注册：{}", userRegisterDTO);
        signService.signup(userRegisterDTO);
        return Result.success();
    }
}
