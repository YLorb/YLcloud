package com.ylcloud.VO;

public record OpenApiEnvelope<T>(T data,OpenApiMeta meta) {
    public static <T> OpenApiEnvelope<T> success(T data,String traceId,String requestedVersion,boolean unversioned) {
        return new OpenApiEnvelope<>(data,new OpenApiMeta(traceId,requestedVersion,"v1",unversioned));
    }
}
