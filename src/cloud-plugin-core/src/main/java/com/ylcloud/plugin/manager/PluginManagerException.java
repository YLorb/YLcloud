package com.ylcloud.plugin.manager;

/** 管理操作的稳定错误；原始插件异常仅作为内部诊断原因保留。 */
public final class PluginManagerException extends RuntimeException {
    public enum Code { ALREADY_INSTALLED, NOT_INSTALLED, PROVIDER_ID_CONFLICT,
        INVALID_STATE, MANIFEST_MISMATCH, UNSUPPORTED_RUNTIME, CONCURRENT_CHANGE }
    private final Code code;

    public PluginManagerException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
