package com.ylcloud.plugin.spi.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentOptionsAndFormulaTest {
    private static final FormulaContent FORMULA = new FormulaContent(FormulaContent.Format.LATEX, "x > 0");

    @Test
    void absentOptionsInExistingConstructorInheritProviderDefaults() {
        var request = new DocumentParseRequest("snapshot", "sample.txt", "text/plain",
                () -> new ByteArrayInputStream(new byte[0]), 10, 1);
        assertEquals(DocumentParseOptions.defaults(), request.options());
        assertNull(request.options().language());
        assertNull(request.options().recognizeTables());
        assertNull(request.options().recognizeFormulas());
    }

    @Test
    void explicitEnableAndDisableRemainDifferentFromUnspecified() {
        var enabled = new DocumentParseOptions(null, true, true);
        var disabled = new DocumentParseOptions(null, false, false);
        assertNotEquals(enabled, disabled);
        assertNotEquals(disabled, DocumentParseOptions.defaults());
        assertEquals(Boolean.FALSE, disabled.recognizeFormulas());
        assertEquals(Boolean.TRUE, enabled.recognizeTables());
    }

    @Test
    void normalizesLanguageWithoutBindingItToAnEngineAlias() {
        assertEquals("zh-CN", new DocumentParseOptions(" zh-cn ", null, null).language());
        assertEquals("zh-Hans", new DocumentParseOptions("zh-hans", false, true).language());
        assertEquals("en", new DocumentParseOptions("EN", null, null).language());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "auto", "und", "zh_CN", "en,zh", "en-"})
    void invalidOrAmbiguousLanguageIsNotSilentlyReplacedByDefaults(String language) {
        assertThrows(IllegalArgumentException.class, () -> new DocumentParseOptions(language, null, null));
    }

    @Test
    void existingPlainTextBlocksDoNotNeedFormulaFields() {
        var block = new DocumentBlock(0, DocumentBlock.Type.TEXT, "plain", 1, null, List.of(), null, null);
        assertNull(block.formula());
        assertTrue(block.inlines().isEmpty());
    }

    @Test
    void standaloneFormulaCarriesStructuredSourceAndPlainTextSeparately() {
        var formula = new FormulaContent(FormulaContent.Format.LATEX, "x^{2}");
        var block = block(DocumentBlock.Type.FORMULA, "x²", formula, List.of());
        assertEquals("x²", block.text());
        assertEquals("x^{2}", block.formula().source());
        assertEquals(FormulaContent.Format.LATEX, block.formula().format());
    }

    @Test
    void standaloneFormulaRequiresPayloadAndRejectsInlineFragments() {
        assertThrows(IllegalArgumentException.class,
                () -> block(DocumentBlock.Type.FORMULA, "x > 0", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> block(DocumentBlock.Type.FORMULA, "x > 0", FORMULA,
                        List.of(new DocumentInline(DocumentInline.Type.FORMULA, "x > 0", FORMULA))));
    }

    @Test
    void nonFormulaBlockCannotCarryStandaloneFormulaPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> block(DocumentBlock.Type.TEXT, "x > 0", FORMULA, List.of()));
    }

    @Test
    void inlineFormulaPreservesSentenceOrderAndCopiesFragments() {
        var parts = new ArrayList<>(List.of(
                new DocumentInline(DocumentInline.Type.TEXT, "当 ", null),
                new DocumentInline(DocumentInline.Type.FORMULA, "x > 0", FORMULA),
                new DocumentInline(DocumentInline.Type.TEXT, " 时", null)));
        var block = block(DocumentBlock.Type.TEXT, "当 x > 0 时", null, parts);
        parts.clear();
        assertEquals(3, block.inlines().size());
        assertEquals(FORMULA, block.inlines().get(1).formula());
        assertNull(block.formula());
        assertThrows(UnsupportedOperationException.class, () -> block.inlines().clear());
    }

    @Test
    void rejectsProjectionThatLosesOrDuplicatesSentenceContent() {
        assertThrows(IllegalArgumentException.class,
                () -> block(DocumentBlock.Type.TEXT, "当 x > 0 时", null,
                        List.of(new DocumentInline(DocumentInline.Type.FORMULA, "x > 0", FORMULA))));
    }

    @Test
    void headingCanContainInlineFormulaButTableNeedsItsOwnFutureCellModel() {
        var parts = List.of(new DocumentInline(DocumentInline.Type.FORMULA, "x > 0", FORMULA));
        assertEquals(parts, block(DocumentBlock.Type.HEADING, "x > 0", null, parts).inlines());
        assertThrows(IllegalArgumentException.class,
                () -> block(DocumentBlock.Type.TABLE, "x > 0", null, parts));
    }

    @Test
    void rejectsAmbiguousInlinePayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentInline(DocumentInline.Type.TEXT, "x > 0", FORMULA));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentInline(DocumentInline.Type.FORMULA, "x > 0", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentInline(DocumentInline.Type.FORMULA, " ", FORMULA));
    }

    @Test
    void formulaFormatAndNonblankSourceAreRequired() {
        assertThrows(NullPointerException.class, () -> new FormulaContent(null, "x"));
        assertThrows(NullPointerException.class, () -> new FormulaContent(FormulaContent.Format.LATEX, null));
        assertThrows(IllegalArgumentException.class, () -> new FormulaContent(FormulaContent.Format.LATEX, " "));
    }

    private static DocumentBlock block(DocumentBlock.Type type, String text,
                                       FormulaContent formula, List<DocumentInline> inlines) {
        return new DocumentBlock(0, type, text, 1, null, List.of(), null, null, formula, inlines);
    }
}
