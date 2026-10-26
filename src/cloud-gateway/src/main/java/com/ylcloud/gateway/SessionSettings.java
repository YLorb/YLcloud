package com.ylcloud.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ylcloud.gateway.session")
public record SessionSettings(boolean enabled, boolean cookieSecure, String identitySecret, Duration timeout) {
    public SessionSettings {
        if (enabled && (identitySecret == null || identitySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32))
            throw new IllegalArgumentException("Gateway identity secret must contain at least 32 bytes");
        if (timeout == null) timeout = Duration.ofSeconds(2);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid Session timeout");
    }
    public String cookieName() { return cookieSecure ? "__Host-ylcloud_session" : "ylcloud_session"; }
}
