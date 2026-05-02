package com.ylcloud.handler;

import com.ylcloud.BaseException;
import com.ylcloud.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice // 该注解可以定义异常通知类,在定义的方法加上 @ExceptionHandler 就可以捕获异常
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(BaseException.class)
    public Result exceptionHandler(BaseException ex) {
        log.error("业务异常：{}",ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /*
        其他没有主动抛出的全局异常，都会在这里被捕获并返回统一的错误信息
     */
    @ExceptionHandler(Exception.class)
    public Result exceptionHandler(Exception ex) {
        log.error("系统异常：", ex);
        return Result.error("系统繁忙，请稍后再试");
    }
}
