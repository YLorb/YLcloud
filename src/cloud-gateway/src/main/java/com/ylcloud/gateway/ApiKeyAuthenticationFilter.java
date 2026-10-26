package com.ylcloud.gateway;

import java.time.Duration;
import java.time.Instant;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyAuthenticationFilter implements GlobalFilter, Ordered {
    private final ApiKeyStore store;
    private final ApiKeyRoutePolicy policy;
    private final ApiKeyIdentityCodec codec;
    public ApiKeyAuthenticationFilter(ApiKeyStore store,ApiKeyRoutePolicy policy,SessionSettings settings) {
        this.store=store; this.policy=policy;
        codec=settings.enabled() ? new ApiKeyIdentityCodec(settings.identitySecret()) : null;
    }
    @Override public int getOrder() { return -80; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain) {
        var current=exchange.mutate().request(exchange.getRequest().mutate().headers(h->h.remove(ApiKeyIdentityCodec.HEADER)).build()).build();
        String path=current.getRequest().getURI().getRawPath();
        if(codec==null || !ApiKeyRoutePolicy.openApi(path)) return chain.filter(current);
        current.getResponse().getHeaders().setCacheControl("no-store");
        var rule=policy.resolve(current.getRequest().getMethod().name(),path);
        if(rule==null) return reject(current,HttpStatus.NOT_FOUND,"NOT_FOUND","接口或请求方法未开放");
        var values=current.getRequest().getHeaders().get("Authorization");
        if(values==null || values.size()!=1 || !values.get(0).matches("Bearer ylk_[A-Za-z0-9_-]{12}_[A-Za-z0-9_-]{43}"))
            return reject(current,HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","API Key 无效、已过期或已撤销");
        return store.authenticate(values.get(0).substring(7)).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .timeout(Duration.ofSeconds(3)).map(Result::new).onErrorReturn(new Result(null)).flatMap(result->{
                if(result.principal()==null) return reject(current,HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_UNAVAILABLE","认证服务暂不可用");
                if(result.principal().isEmpty()) return reject(current,HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","API Key 无效、已过期或已撤销");
                var principal=result.principal().get();
                if(!rule.anyScope().isEmpty() && rule.anyScope().stream().noneMatch(principal.scopes()::contains))
                    return reject(current,HttpStatus.FORBIDDEN,"FORBIDDEN","API Key 未授权执行此操作");
                if(rule.write() && !principal.readOnlyAfter().isBlank()) {
                    try { if(!Instant.parse(principal.readOnlyAfter().trim()).isAfter(Instant.now()))
                        return reject(current,HttpStatus.GONE,"API_VERSION_READ_ONLY","API 版本已进入只读阶段"); }
                    catch(java.time.format.DateTimeParseException invalid) { return reject(current,HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_UNAVAILABLE","API 版本策略配置无效"); }
                }
                String target=path+(current.getRequest().getURI().getRawQuery()==null ? "" : "?"+current.getRequest().getURI().getRawQuery());
                String signed=codec.sign(principal.keyId(),principal.userId(),current.getRequest().getHeaders().getFirst("X-Request-Id"),current.getRequest().getMethod().name(),target);
                return chain.filter(current.mutate().request(current.getRequest().mutate().headers(h->{
                    h.remove("Authorization"); h.set(ApiKeyIdentityCodec.HEADER,signed);
                }).build()).build());
            });
    }
    private record Result(java.util.Optional<ApiKeyStore.Principal> principal) {}
    private Mono<Void> reject(ServerWebExchange exchange,HttpStatus status,String code,String message) {
        var response=exchange.getResponse(); response.setStatusCode(status);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        response.getHeaders().set("X-API-Version","v1"); response.getHeaders().set("X-API-Latest-Version","v1");
        String trace=exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        response.getHeaders().set("X-Trace-Id",trace);
        String json="{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\",\"details\":{}},\"meta\":{\"traceId\":\""+trace+"\",\"version\":\"v1\",\"latestVersion\":\"v1\",\"unversionedAlias\":"+exchange.getRequest().getURI().getRawPath().startsWith("/api/open")+"}}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
