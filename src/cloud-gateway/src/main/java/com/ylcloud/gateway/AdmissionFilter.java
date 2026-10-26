package com.ylcloud.gateway;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AdmissionFilter implements GlobalFilter, Ordered {
    private final SlidingWindowLimiter limiter;
    private final ClientAddress addresses;
    private final GatewaySettings settings;
    public AdmissionFilter(SlidingWindowLimiter limiter, ClientAddress addresses, GatewaySettings settings) {
        this.limiter = limiter; this.addresses = addresses; this.settings = settings;
    }
    @Override public int getOrder() { return -100; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final String address;
        try { address = addresses.resolve(exchange); }
        catch (IllegalArgumentException invalid) { return reject(exchange, HttpStatus.BAD_REQUEST, "客户端地址无效", 0); }
        // Catch only limiter failures. Upstream failures must not be mislabeled Redis failures.
        return limiter.acquire(address).map(Attempt::new).onErrorResume(error -> Mono.just(new Attempt(null)))
                .flatMap(attempt -> {
                    if (attempt.decision == null) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用，请稍后重试", 1);
                    if (!attempt.decision.allowed()) {
                        exchange.getResponse().getHeaders().set("X-YLCloud-RateLimit", "gateway");
                        return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试", attempt.decision.retryAfterSeconds());
                    }
                    var request = exchange.getRequest().mutate().headers(headers -> {
                        headers.remove("Forwarded");
                        headers.headerNames().stream().filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-")).toList().forEach(headers::remove);
                        headers.remove("X-User-Id");
                        headers.remove("X-Username");
                        headers.set("X-Real-IP", address);
                        headers.set("X-YLCloud-Client-IP", address);
                        headers.set("X-YLCloud-Gateway-Proof", settings.forwardingSecret());
                        headers.set("X-Request-Id", UUID.randomUUID().toString());
                    }).build();
                    exchange.getResponse().getHeaders().set("X-Request-Id", request.getHeaders().getFirst("X-Request-Id"));
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }
    static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message, long retry) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        if (retry > 0) response.getHeaders().set("Retry-After", Long.toString(retry));
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
    private record Attempt(SlidingWindowLimiter.Decision decision) {}
}
