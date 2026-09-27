package com.ylcloud.plugin.spi.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * Host-authorized, repeatable document bytes. Each call opens a fresh non-null
 * stream at offset zero over the same snapshot, valid until parse returns.
 * The provider closes every stream it opens, including on failure. It must not
 * retain the source or read asynchronously after returning. The host owns the
 * backing snapshot. This is a Java port, not an HTTP payload: remote adapters
 * transfer the bytes. It exposes no database entity, storage credential or URL.
 */
@FunctionalInterface
public interface DocumentContent {
    InputStream openStream() throws IOException;
}
