package com.ylcloud.plugin.spi.document;

import java.util.List;
import java.util.Objects;

/**
 * Complete successful parse. Empty text/blocks is a valid empty document; quality
 * acceptance belongs to the host. Warnings are safe diagnostics, not failures or
 * permission to truncate. Failures are exceptions, never a contradictory flag.
 */
public record DocumentParseResult(String fullText, List<DocumentBlock> blocks, List<String> warnings) {
    public DocumentParseResult {
        Objects.requireNonNull(fullText, "fullText");
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).orderIndex() != i) {
                throw new IllegalArgumentException("block indices must be contiguous reading order from zero");
            }
        }
    }
}
