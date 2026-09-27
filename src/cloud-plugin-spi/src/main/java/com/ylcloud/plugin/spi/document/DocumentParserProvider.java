package com.ylcloud.plugin.spi.document;

import com.ylcloud.plugin.spi.PluginProvider;
import com.ylcloud.plugin.spi.ProviderCallContext;
import com.ylcloud.plugin.spi.ProviderException;

import java.util.Set;

/** Business capability implemented by a document parsing plugin author. */
public interface DocumentParserProvider extends PluginProvider {
    /**
     * Stable, non-empty, immutable set of lowercase exact MIME types, without I/O.
     * Declare only formats this implementation actually handles. The set is a
     * routing hint; parse must still validate the request and actual bytes.
     */
    Set<String> supportedMediaTypes();

    /**
     * Synchronous, complete parse of the authorized snapshot. Implementations
     * must tolerate concurrent calls without cross-request state leakage (they
     * may serialize access to a non-thread-safe engine within the call budget).
     * Check the deadline/interruption before opening content and between bounded
     * steps; enforce byte/page limits, close streams and return a non-null result.
     * Apply per-call options over configured defaults. Reject unsupported explicit
     * options with UNSUPPORTED_OPTION before reading content; never silently ignore
     * them. A false recognition flag does not forbid native structured extraction
     * from an input format; it disables that recognition feature.
     * Do not start detached work, silently truncate or substitute metadata text.
     * Expected failures use ProviderException; interruption propagates separately.
     * Transport, engine, deployment and scheduling choices stay behind this port.
     */
    DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
            throws ProviderException, InterruptedException;
}
