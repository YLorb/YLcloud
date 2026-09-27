package com.ylcloud.plugin.spi.document;

import com.ylcloud.plugin.spi.ProviderCallContext;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentContractValueTest {
    @Test
    void resultDefensivelyCopiesEveryCollectionIncludingNestedHeadingPath() {
        var headings = new ArrayList<>(List.of("Chapter"));
        var blocks = new ArrayList<>(List.of(block(0, headings, 0.9)));
        var warnings = new ArrayList<>(List.of("low contrast"));
        var result = new DocumentParseResult("text", blocks, warnings);
        headings.clear();
        blocks.clear();
        warnings.clear();
        assertEquals(List.of("Chapter"), result.blocks().get(0).headingPath());
        assertEquals(List.of("low contrast"), result.warnings());
        assertThrows(UnsupportedOperationException.class, () -> result.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.blocks().get(0).headingPath().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().clear());
    }

    @Test
    void resultRejectsDuplicateMissingOrReorderedIndices() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentParseResult("text", List.of(block(1, List.of(), null)), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentParseResult("text", List.of(block(0, List.of(), null), block(0, List.of(), null)), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentParseResult("text", List.of(block(0, List.of(), null), block(2, List.of(), null)), List.of()));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1})
    void rejectsInvalidConfidence(double confidence) {
        assertThrows(IllegalArgumentException.class, () -> block(0, List.of(), confidence));
    }

    @Test
    void unknownConfidenceAndInclusiveConfidenceBoundariesArePreserved() {
        assertNull(block(0, List.of(), null).confidence());
        assertEquals(0.0, block(0, List.of(), 0.0).confidence());
        assertEquals(1.0, block(0, List.of(), 1.0).confidence());
    }

    @Test
    void rejectsInvalidGeometryRatherThanMixingPixelAndNormalizedCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 100, 200));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0.8, 0, 0.2, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(Double.NaN, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 1, Double.POSITIVE_INFINITY));
        assertEquals(1.0, new BoundingBox(0, 0, 1, 1).right());
    }

    @Test
    void pageAndHeadingMetadataHaveUnambiguousMeaning() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentBlock(0, DocumentBlock.Type.TEXT, "text", 0, null, List.of(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentBlock(0, DocumentBlock.Type.TEXT, "text", 1, 1, List.of(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentBlock(0, DocumentBlock.Type.HEADING, "text", 1, 0, List.of(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentBlock(0, DocumentBlock.Type.TEXT, "text", null, null, List.of(), new BoundingBox(0, 0, 1, 1), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/*", "text/plain;charset=UTF-8", "text", "text/", ""})
    void rejectsAmbiguousMediaTypes(String mediaType) {
        assertThrows(IllegalArgumentException.class, () -> request(mediaType, 10, 1));
    }

    @Test
    void normalizesMediaTypeAndRejectsUnboundedLimits() {
        assertEquals("application/pdf", request(" Application/PDF ", 10, 1).mediaType());
        assertThrows(IllegalArgumentException.class, () -> request("text/plain", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> request("text/plain", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> request("text/plain", 10, 0));
        assertThrows(IllegalArgumentException.class, () -> request("text/plain", 10, -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../paddle", "PaddleOCR", "paddle ocr"})
    void rejectsAmbiguousProviderIdentity(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ProviderDescriptor(id, "1.0.0"));
    }

    @Test
    void rejectsMissingIdentityVersionAndTracingFields() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderDescriptor("paddleocr", " "));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDescriptor("paddleocr", " 1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCallContext(" ", Instant.MAX));
        assertThrows(NullPointerException.class, () -> new ProviderCallContext("call", null));
        assertThrows(NullPointerException.class, () -> new DocumentParseResult(null, List.of(), List.of()));
    }

    private static DocumentBlock block(int index, List<String> path, Double confidence) {
        return new DocumentBlock(index, DocumentBlock.Type.TEXT, "text", 1, null, path, null, confidence);
    }

    private static DocumentParseRequest request(String type, long maxBytes, int maxPages) {
        return new DocumentParseRequest("snapshot", "sample", type,
                () -> new ByteArrayInputStream(new byte[0]), maxBytes, maxPages);
    }
}
