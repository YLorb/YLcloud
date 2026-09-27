package com.ylcloud.plugin.runtime;

/** 对外稳定错误，cause 仅供受控诊断，不能直接输出到 HTTP 响应。 */
public final class PluginRuntimeException extends RuntimeException {
    public enum Code { START_FAILED, STOP_FAILED }
    private final Code code;
    public PluginRuntimeException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
    public Code code() { return code; }
}
