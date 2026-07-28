package com.ylcloud.VO;

import java.util.Map;

public record OpenApiErrorEnvelope(OpenApiError error,OpenApiMeta meta) {
    public record OpenApiError(String code,String message,Map<String,Object> details) {
    }
}
