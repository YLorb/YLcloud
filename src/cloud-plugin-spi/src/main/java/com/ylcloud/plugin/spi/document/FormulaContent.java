package com.ylcloud.plugin.spi.document;

import java.util.Objects;

/**
 * Structured formula content, separate from a block/inline's plain-text projection.
 * LATEX source is the formula body without surrounding dollar/display delimiters.
 * It is recognized content, not proof of mathematical correctness or trusted code.
 * This value validates shape only; the host must use an appropriate safe renderer.
 */
public record FormulaContent(Format format, String source) {
    public FormulaContent {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("formula source must not be blank");
        }
    }

    public enum Format { LATEX }
}
