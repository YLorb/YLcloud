package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.utils.HashUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Fail-closed client for the fixed file.preflight Sandbox tool. */
@Service
public class SpaceFilePreflightService {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${ylcloud.space-import.sandbox.enabled:false}") private boolean enabled;
    @Value("${ylcloud.space-import.sandbox.base-url:http://sandbox-service:8004}") private String baseUrl;
    @Value("${ylcloud.space-import.sandbox.token:}") private String token;
    @Value("${ylcloud.space-import.sandbox.token-file:}") private String tokenFile;

    public SpaceFilePreflightService(ObjectMapper json) { this.json = json; }

    public String inspect(String fileName, String contentHash, long size, InputStream input, Long userId) {
        if(!enabled) throw new BaseException(503,"Sandbox 预检未启用，导入已拒绝");
        String invocationId = "pf_" + UUID.randomUUID().toString().replace("-","");
        Path requestFile = null;
        try(InputStream content = input) {
            requestFile = Files.createTempFile("ylcloud-space-preflight-",".json");
            try(var output = Files.newOutputStream(requestFile);
                JsonGenerator generator = json.getFactory().createGenerator(output)) {
                generator.writeStartObject();
                generator.writeStringField("contract_version","1.0");
                generator.writeStringField("invocation_id",invocationId);
                generator.writeStringField("idempotency_key",buildIdempotencyKey(fileName,contentHash,size,userId));
                generator.writeStringField("tool_name","file.preflight");
                generator.writeStringField("tool_version","1.0.0");
                generator.writeObjectFieldStart("arguments");
                generator.writeStringField("fileName",fileName);
                generator.writeStringField("sha256",contentHash);
                generator.writeNumberField("size",size);
                generator.writeFieldName("contentBase64");
                generator.writeBinary(content,-1);
                generator.writeEndObject();
                generator.writeObjectFieldStart("parent_trace");
                generator.writeStringField("trace_id",UUID.randomUUID().toString().replace("-",""));
                generator.writeStringField("span_id",UUID.randomUUID().toString().replace("-","").substring(0,16));
                generator.writeEndObject();
                generator.writeStringField("subject_id",String.valueOf(userId));
                generator.writeNumberField("timeout_seconds",120);
                generator.writeEndObject();
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/invocations"))
                    .timeout(Duration.ofSeconds(150)).header("Content-Type","application/json")
                    .header("Authorization","Bearer " + resolveToken())
                    .POST(HttpRequest.BodyPublishers.ofFile(requestFile)).build();
            HttpResponse<String> response = http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BaseException(503,"Sandbox 预检服务不可用");
            }
            JsonNode body = json.readTree(response.body());
            validateResult(body);
            return body.path("invocation_id").asText(invocationId);
        } catch(BaseException ex) {
            throw ex;
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BaseException(503,"Sandbox 预检被中断，导入已拒绝");
        } catch(Exception ex) {
            throw new BaseException(503,"Sandbox 预检失败，导入已拒绝");
        } finally {
            if(requestFile != null) try { Files.deleteIfExists(requestFile); } catch(Exception ignored) { }
        }
    }

    static String buildIdempotencyKey(String fileName, String contentHash, long size, Long userId) {
        // The preflight result depends on the content and the declared file metadata. Trace and
        // invocation IDs are deliberately excluded so an exact retry reuses the cached result.
        String requestFingerprint = HashUtil.sha256(fileName + "\u0000" + contentHash + "\u0000" + size);
        return "space-preflight:" + userId + ":" + requestFingerprint;
    }

    static void validateResult(JsonNode body) {
        if(!"SUCCEEDED".equals(body.path("status").asText())) {
            throw new BaseException(503,"Sandbox 预检执行失败，导入已拒绝");
        }
        if(!body.path("result").path("safe").asBoolean(false)) {
            throw new BaseException("文件未通过 Sandbox 安全预检");
        }
    }

    private String resolveToken() throws Exception {
        String value = token == null ? "" : token.trim();
        if(value.isEmpty() && tokenFile != null && !tokenFile.isBlank()) value = Files.readString(Path.of(tokenFile)).trim();
        if(value.length() < 32) throw new BaseException(503,"Sandbox 服务凭据未配置");
        return value;
    }
}
