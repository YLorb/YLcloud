package com.ylcloud.webhook;

import com.ylcloud.Exception.BaseException;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WebhookHttpClient {
    private static final Set<Integer> REDIRECTS = Set.of(301,302,303,307,308);
    private final WebhookTargetPolicy targetPolicy;

    public WebhookHttpClient(WebhookTargetPolicy targetPolicy) {
        this.targetPolicy = targetPolicy;
    }

    public int post(String target,String body,Map<String,String> headers,Duration timeout) throws Exception {
        URI current = URI.create(target);
        for(int redirect=0;redirect<=3;redirect++) {
            // 每一跳重新解析并把连接固定到已校验 IP，避免校验后再次 DNS 解析产生 TOCTOU/rebinding。
            WebhookTargetPolicy.ResolvedTarget resolved = targetPolicy.resolveAllowed(current.toString());
            current = resolved.uri();
            RawResponse response = sendPinned(resolved,body,headers,timeout);
            if(!REDIRECTS.contains(response.status())) return response.status();
            if(redirect == 3) throw new BaseException("Webhook 重定向次数超过上限");
            String location = response.headers().get("location");
            if(location == null || location.isBlank()) throw new BaseException("Webhook 重定向缺少 Location");
            current = current.resolve(location);
        }
        throw new BaseException("Webhook 重定向失败");
    }

    private RawResponse sendPinned(WebhookTargetPolicy.ResolvedTarget target,String body,Map<String,String> headers,
                                   Duration timeout) throws Exception {
        Exception last = null;
        for(InetAddress address : target.addresses()) {
            try {
                return sendOne(target.uri(),address,body,headers,timeout);
            } catch(Exception exception) {
                last = exception;
            }
        }
        if(last != null) throw last;
        throw new BaseException("Webhook 目标没有可用 IP");
    }

    private RawResponse sendOne(URI uri,InetAddress address,String body,Map<String,String> headers,
                                Duration timeout) throws Exception {
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        int timeoutMs = Math.max(1000,(int)Math.min(60_000,timeout.toMillis()));
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(address,port),timeoutMs);
        Socket socket = raw;
        try {
            if(tls) {
                SSLSocket ssl = (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
                        .createSocket(raw,uri.getHost(),port,true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                try {
                    parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                } catch(IllegalArgumentException ignored) {
                    // IP literal targets have no valid SNI hostname; endpoint identification still verifies the IP SAN.
                }
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                socket = ssl;
            }
            socket.setSoTimeout(timeoutMs);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if(uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String host = uri.getHost() + ((uri.getPort() > 0) ? ":" + uri.getPort() : "");
            StringBuilder request = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Content-Type: application/json; charset=utf-8\r\n")
                    .append("User-Agent: YLCloud-Webhook/1.0\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n");
            headers.forEach((name,value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if(statusLine == null || !statusLine.matches("HTTP/\\d(?:\\.\\d)? \\d{3}.*")) {
                throw new BaseException("Webhook 响应状态行无效");
            }
            int status = Integer.parseInt(statusLine.split(" ",3)[1]);
            Map<String,String> responseHeaders = new LinkedHashMap<>();
            String line;
            while((line = reader.readLine()) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if(separator > 0) responseHeaders.put(line.substring(0,separator).trim().toLowerCase(),line.substring(separator+1).trim());
            }
            return new RawResponse(status,responseHeaders);
        } finally {
            socket.close();
            if(socket != raw) raw.close();
        }
    }

    private record RawResponse(int status,Map<String,String> headers) { }
}
