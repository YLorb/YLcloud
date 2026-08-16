package com.ylcloud.workflow.security;

/** 固定错误码异常；不得包含 Token、Secret 或底层验签异常正文。 */
public class ServiceJwtException extends RuntimeException {
    private final String code;

    public ServiceJwtException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
