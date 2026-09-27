package com.ylcloud.plugin.spi;

import java.util.Objects;

/**
 * Expected provider failure. Messages must be safe diagnostics without document
 * contents, credentials or signed URLs. Causes may contain private diagnostics:
 * the host must not expose or blindly log them. Codes do not authorize retries.
 */
public final class ProviderException extends Exception {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public ProviderException(Code code, String message) {
        this(code, message, null);
    }

    public ProviderException(Code code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        UNSUPPORTED_MEDIA_TYPE,
        UNSUPPORTED_OPTION,
        INVALID_DOCUMENT,
        INPUT_READ_FAILED,
        LIMIT_EXCEEDED,
        DEADLINE_EXCEEDED,
        UNAVAILABLE,
        INTERNAL_ERROR
    }
}
