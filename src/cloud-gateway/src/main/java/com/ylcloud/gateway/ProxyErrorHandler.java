package com.ylcloud.gateway;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class ProxyErrorHandler implements WebExceptionHandler {
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        if (error instanceof ResponseStatusException status) return AdmissionFilter.reject(exchange,
                HttpStatus.valueOf(status.getStatusCode().value()), "请求无法完成", 0);
        boolean timeout = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException)
                timeout = true;
        }
        return AdmissionFilter.reject(exchange, timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                timeout ? "上游服务响应超时" : "上游服务暂不可用", 0);
    }
}
