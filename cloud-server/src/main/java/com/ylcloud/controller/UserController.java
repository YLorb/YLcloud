package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    /**
     * 执行 currentUser 函数的业务处理。
     * @return 接口响应结果
     */
    @GetMapping("/current")
    public Result<Long> currentUser() {
        return Result.success(BaseContext.getCurrentId());
    }
}
