package com.ylcloud.gateway;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class ClientAddress {
    private final List<String> trusted;
    public ClientAddress(GatewaySettings settings) {
        trusted = settings.trustedProxies().stream().filter(value -> !value.isBlank()).map(ClientAddress::normalize).toList();
    }
    public String resolve(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) throw new IllegalArgumentException("Missing client address");
        String peer = normalize(remote.getAddress().getHostAddress());
        // The trusted edge must overwrite this header, not append untrusted client input.
        if (!trusted.contains(peer)) return peer;
        var values = exchange.getRequest().getHeaders().get("X-Real-IP");
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Missing trusted client address");
        return normalize(values.get(0));
    }
    static String normalize(String value) {
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        if (bytes == null || value.contains("%")) throw new IllegalArgumentException("Invalid client address");
        try { return InetAddress.getByAddress(bytes).getHostAddress(); }
        catch (java.net.UnknownHostException impossible) { throw new IllegalArgumentException(impossible); }
    }
}
