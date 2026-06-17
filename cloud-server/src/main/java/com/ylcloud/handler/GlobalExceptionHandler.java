package com.ylcloud.handler;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Result<?>> handleBaseException(BaseException ex) {
        String message = ex.getMessage();
        log.warn("业务异常: {}", message);
        if(isPermissionDenied(message)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403,message));
        }
        return ResponseEntity.ok(Result.error(message));
    }

    private boolean isPermissionDenied(String message) {
        if(message == null) {
            return false;
        }
        return message.contains("权限") || message.contains("无权") || message.startsWith("只有");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return Result.error(message);
    }

    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        log.warn("参数绑定失败: {}", message);
        return Result.error(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        String message = "缺少请求参数: " + ex.getParameterName();
        log.warn("参数缺失: {}", message);
        return Result.error(message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String message = "请求参数类型错误: " + ex.getName();
        log.warn("参数类型错误: {}", message);
        return Result.error(message);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error("系统繁忙，请稍后再试");
    }
}
