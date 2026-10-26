package com.ylcloud.gateway;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ylcloud.gateway")
public record GatewaySettings(String upstream, List<String> trustedProxies, String forwardingSecret,
                              Duration redisTimeout, String keyPrefix) {
    public GatewaySettings {
        if (upstream == null) upstream = "http://127.0.0.1:8080";
        if (trustedProxies == null) trustedProxies = List.of();
        if (redisTimeout == null) redisTimeout = Duration.ofSeconds(1);
        if (keyPrefix == null) keyPrefix = "ylcloud:gateway:";
        if (redisTimeout.isNegative() || redisTimeout.isZero()) throw new IllegalArgumentException("Invalid Redis timeout");
        if (forwardingSecret == null || forwardingSecret.length() < 32)
            throw new IllegalArgumentException("Gateway forwarding secret must contain at least 32 characters");
    }
}
