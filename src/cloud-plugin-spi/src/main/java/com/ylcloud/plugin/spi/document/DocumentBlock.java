package com.ylcloud.plugin.spi.document;

import java.util.List;
import java.util.Objects;

/**
 * A block in reading order. orderIndex is zero-based; pageNo and headingLevel
 * are one-based or null when unavailable. text is plain text (possibly empty),
 * not executable HTML. A table's text does not imply a cell/grid representation.
 * headingPath runs outermost to innermost; unknown confidence is null, not 1.0.
 * formula is required exactly for standalone FORMULA blocks. inlines is an
 * optional ordered decomposition of TEXT/HEADING text; its text projections must
 * concatenate to text exactly. Empty inlines means no decomposition is supplied.
 * Table cells and formulas inside those cells are not modeled by this record.
 */
public record DocumentBlock(int orderIndex, Type type, String text, Integer pageNo,
                            Integer headingLevel, List<String> headingPath,
                            BoundingBox boundingBox, Double confidence,
                            FormulaContent formula, List<DocumentInline> inlines) {
    /** Convenience constructor for blocks without structured formula content. */
    public DocumentBlock(int orderIndex, Type type, String text, Integer pageNo,
                         Integer headingLevel, List<String> headingPath,
                         BoundingBox boundingBox, Double confidence) {
        this(orderIndex, type, text, pageNo, headingLevel, headingPath, boundingBox,
                confidence, null, List.of());
    }

    public DocumentBlock {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        headingPath = List.copyOf(headingPath);
        inlines = List.copyOf(inlines);
        if ((type == Type.FORMULA) != (formula != null)) {
            throw new IllegalArgumentException("only FORMULA blocks require formula content");
        }
        if (!inlines.isEmpty()) {
            if (type != Type.TEXT && type != Type.HEADING) {
                throw new IllegalArgumentException("inline fragments require a text or heading block");
            }
            StringBuilder projection = new StringBuilder();
            for (DocumentInline inline : inlines) {
                projection.append(inline.text());
            }
            if (!text.contentEquals(projection)) {
                throw new IllegalArgumentException("inline projections must match block text");
            }
        }
        if (orderIndex < 0 || (pageNo != null && pageNo < 1)) {
            throw new IllegalArgumentException("invalid block index or page number");
        }
        if (headingLevel != null && (headingLevel < 1 || type != Type.HEADING)) {
            throw new IllegalArgumentException("headingLevel requires a heading with positive level");
        }
        if (boundingBox != null && pageNo == null) {
            throw new IllegalArgumentException("boundingBox requires a page number");
        }
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw new IllegalArgumentException("confidence must be finite and within [0, 1]");
        }
    }

    public enum Type { TEXT, HEADING, TABLE, IMAGE, FORMULA, OTHER }
}
