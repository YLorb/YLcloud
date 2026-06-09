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
     * 获取当前登录用户 ID。
     *
     * @return 当前登录用户 ID
     */
    @GetMapping("/current")
    public Result<Long> currentUser() {
        return Result.success(BaseContext.getCurrentId());
    }
}
