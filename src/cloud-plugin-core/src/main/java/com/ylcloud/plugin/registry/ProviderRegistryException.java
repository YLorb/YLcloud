package com.ylcloud.plugin.registry;

import java.util.Objects;

/** 注册/查找失败，与 Provider 执行业务时的 ProviderException 分开。原因链不得直接对外暴露。 */
public final class ProviderRegistryException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public ProviderRegistryException(Code code, String message) {
        this(code, message, null);
    }

    public ProviderRegistryException(Code code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code { INVALID_METADATA, DUPLICATE_ID, NOT_FOUND, CAPABILITY_MISMATCH }
}
