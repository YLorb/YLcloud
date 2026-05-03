package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController //这个类是一个控制器，并且它的方法返回值直接作为 HTTP 响应内容返回。
@RequestMapping("/user")
public class User {
    @GetMapping("/current")
    public Result<Long> currentUser() {
        return Result.success(BaseContext.getCurrentId());
    }
}
