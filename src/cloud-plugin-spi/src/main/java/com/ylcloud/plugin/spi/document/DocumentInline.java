package com.ylcloud.plugin.spi.document;

import java.util.Objects;

/**
 * Ordered text or formula inside a paragraph/heading. text is the plain-text
 * projection used for fallback display/search, including for formula fragments.
 * Position within the sentence is the position in the block's inlines list.
 */
public record DocumentInline(Type type, String text, FormulaContent formula) {
    public DocumentInline {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if ((type == Type.FORMULA) != (formula != null)) {
            throw new IllegalArgumentException("only formula fragments require formula content");
        }
        if (type == Type.FORMULA && text.isBlank()) {
            throw new IllegalArgumentException("formula fragment requires a plain-text projection");
        }
    }

    public enum Type { TEXT, FORMULA }
}
