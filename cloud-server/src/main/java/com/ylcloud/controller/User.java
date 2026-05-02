package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;


@RequestMapping("/user")
public class User {
    @GetMapping("/current")
    public Result<Long> currentUser() {
        return Result.success(BaseContext.getCurrentId());
    }
}
