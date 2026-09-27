package com.ylcloud.plugin.spi.document;

import java.util.Locale;
import java.util.Objects;

/** 请求与 Registry 共用的具体 MIME 类型规则，不接受通配符或参数。 */
public record DocumentMediaType(String value) {
    public DocumentMediaType {
        Objects.requireNonNull(value, "value");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
            throw new IllegalArgumentException("mediaType must be an exact MIME type without parameters");
        }
    }
}
