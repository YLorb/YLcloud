package com.ylcloud.plugin.spi.document;

import java.util.Objects;

/**
 * Input snapshot and hard processing limits. mediaType is an exact MIME type,
 * case-normalized without parameters or wildcards. fileName is display metadata,
 * never a trusted filesystem path. documentId identifies a host snapshot; neither
 * it nor the MIME type is proof of authorization or actual content type.
 * Providers must reject limits exceeded, never report partial parsing as complete.
 */
public record DocumentParseRequest(String documentId, String fileName, String mediaType,
                                   DocumentContent content, long maxInputBytes, int maxPages,
                                   DocumentParseOptions options) {
    /** Existing callers inherit provider defaults by omitting per-call options. */
    public DocumentParseRequest(String documentId, String fileName, String mediaType,
                                DocumentContent content, long maxInputBytes, int maxPages) {
        this(documentId, fileName, mediaType, content, maxInputBytes, maxPages,
                DocumentParseOptions.defaults());
    }

    public DocumentParseRequest {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        if (documentId.isBlank() || fileName.isBlank()) {
            throw new IllegalArgumentException("documentId and fileName must not be blank");
        }
        mediaType = new DocumentMediaType(mediaType).value();
        if (maxInputBytes <= 0 || maxPages <= 0) {
            throw new IllegalArgumentException("input byte and page limits must be positive");
        }
    }
}
