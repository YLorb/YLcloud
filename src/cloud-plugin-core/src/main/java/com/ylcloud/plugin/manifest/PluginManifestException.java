package com.ylcloud.plugin.manifest;

import java.util.Objects;

/** 对外错误只包含分类与安全位置说明；底层原因可能含输入内容，不得直接暴露或盲目记录。 */
public final class PluginManifestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public PluginManifestException(Code code, String message) {
        this(code, message, null);
    }

    public PluginManifestException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }

    public enum Code {
        READ_FAILED, TOO_LARGE, INVALID_JSON, INVALID_MANIFEST,
        UNSUPPORTED_SCHEMA_VERSION, INCOMPATIBLE_SPI_VERSION
    }
}
