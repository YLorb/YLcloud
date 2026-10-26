package com.ylcloud.handler;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.ShortLinkAbuseGuard;
import com.ylcloud.service.ShortLinkAccessLog;
import com.ylcloud.utils.ShortLinkUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-10)
public class ShortLinkExceptionHandler {
    private final ShortLinkAbuseGuard guard;
    private final ShortLinkAccessLog accessLog;
    public ShortLinkExceptionHandler(ShortLinkAbuseGuard guard, ShortLinkAccessLog accessLog) {
        this.guard = guard; this.accessLog = accessLog;
    }

    @ExceptionHandler(ShortLinkAbuseGuard.Missing.class)
    public ResponseEntity<Result<?>> missing(HttpServletRequest request) {
        long retry;
        try { retry = guard.missing(request.getRemoteAddr()); }
        catch (com.ylcloud.Exception.BaseException failure) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store").body(Result.error(503, failure.getMessage()));
        }
        String address = request.getRequestURI();
        if (address.length() > 512) address = address.substring(0, 512);
        accessLog.record("SHORT_LINK_MISSING", BaseContext.getCurrentId(), request.getRemoteAddr(), address, "", null, "MISSING");
        var response = ResponseEntity.status(502).header("Cache-Control", "no-store");
        if (retry > 0) response.header("Retry-After", Long.toString(retry));
        return response.body(Result.error(502, "短链接不存在或访问暂时受限"));
    }

    @ExceptionHandler(ShortLinkUtil.ClockRollbackException.class)
    public ResponseEntity<Result<?>> clock(ShortLinkUtil.ClockRollbackException failure) {
        var response = ResponseEntity.status(503).header("Cache-Control", "no-store");
        if (!failure.excessive()) response.header("Retry-After", Long.toString(Math.max(1, (failure.rollbackMillis() + 999) / 1000)));
        return response.body(Result.error(503, failure.excessive()
                ? "节点时钟异常，短链接生成已停止" : "节点正在等待时钟恢复，请稍后重试"));
    }
}
