package com.ylcloud.context;

import com.ylcloud.security.ApiKeyPrincipal;

public final class OpenApiContext {
    private static final ThreadLocal<ApiKeyPrincipal> PRINCIPAL = new ThreadLocal<>();
    private OpenApiContext() { }
    public static void set(ApiKeyPrincipal principal) { PRINCIPAL.set(principal); }
    public static ApiKeyPrincipal require() {
        ApiKeyPrincipal value = PRINCIPAL.get();
        if(value == null) throw new IllegalStateException("open API principal unavailable");
        return value;
    }
    public static void clear() { PRINCIPAL.remove(); }
}
