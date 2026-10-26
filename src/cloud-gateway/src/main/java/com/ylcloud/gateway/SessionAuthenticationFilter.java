package com.ylcloud.gateway;

import java.util.Optional;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class SessionAuthenticationFilter implements GlobalFilter, Ordered {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionAuthenticationFilter.class);
    private final GatewaySessionStore store;
    private final SessionSettings settings;
    private final GatewayIdentityCodec identities;
    public SessionAuthenticationFilter(GatewaySessionStore store, SessionSettings settings) {
        this.store = store; this.settings = settings;
        identities = settings.enabled() ? new GatewayIdentityCodec(settings.identitySecret()) : null;
    }
    @Override public int getOrder() { return -90; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Always strip caller-supplied assertions, including during an explicit compatibility rollout.
        exchange = exchange.mutate().request(exchange.getRequest().mutate().headers(h -> h.remove(GatewayIdentityCodec.HEADER)).build()).build();
        if (!settings.enabled()) return chain.filter(exchange);
        var request = exchange.getRequest();
        String path = request.getURI().getRawPath();
        boolean openApi = under(path, "/api/v1") || under(path, "/api/open");
        boolean publicRoute = Set.of("/api/login", "/api/sign", "/api/site/public-settings").contains(path)
                || under(path, "/api/share") || under(path, "/api/public/share-links");
        var cookies = request.getCookies().get(settings.cookieName());
        String credential = cookies != null && cookies.size() == 1 && cookies.get(0).getValue().matches("[A-Za-z0-9_-]{43}")
                ? cookies.get(0).getValue() : null;
        // Open API keeps its API Key contract. Optional login is used only by public shares.
        boolean lookup = !openApi && credential != null && (!publicRoute || under(path, "/api/public/share-links"));
        Mono<Optional<GatewayIdentityCodec.Identity>> identity = lookup
                ? store.authenticate(credential).map(Optional::of).defaultIfEmpty(Optional.empty()).timeout(settings.timeout())
                : Mono.just(Optional.empty());
        final ServerWebExchange current = exchange;
        return identity.map(Result::new).onErrorResume(error -> {
            log.warn("Session store lookup failed", error);
            return Mono.just(new Result(null));
        }).flatMap(result -> {
            if (result.identity == null) return AdmissionFilter.reject(current, HttpStatus.SERVICE_UNAVAILABLE, "登录状态服务暂不可用，请稍后重试", 1);
            if (!openApi && !publicRoute && result.identity.isEmpty()) {
                current.getResponse().addCookie(ResponseCookie.from(settings.cookieName(), "").httpOnly(true).secure(settings.cookieSecure())
                        .sameSite("Lax").path("/").maxAge(0).build());
                return AdmissionFilter.reject(current, HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录", 0);
            }
            String target = path + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());
            String signed = identities.sign(result.identity.orElse(null), request.getMethod().name(), target, credential);
            return chain.filter(current.mutate().request(current.getRequest().mutate().headers(h -> h.set(GatewayIdentityCodec.HEADER, signed)).build()).build());
        });
    }
    private static boolean under(String path, String prefix) { return path.equals(prefix) || path.startsWith(prefix + "/"); }
    private record Result(Optional<GatewayIdentityCodec.Identity> identity) {}
}
