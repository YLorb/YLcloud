package com.ylcloud.plugin.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ylcloud.plugin.registry.ProviderRegistry;
import com.ylcloud.plugin.spi.PluginSpi;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PluginManifestReader reader = new PluginManifestReader();

    @Test
    void readsDocumentedExampleWithoutRegisteringAnything() throws Exception {
        var registry = new ProviderRegistry();
        var manifest = read(example());
        assertEquals("example.paddleocr", manifest.id());
        assertEquals(PluginSpi.VERSION, manifest.spiVersion());
        assertEquals(Set.of(PluginManifest.RuntimeMode.DOCKER, PluginManifest.RuntimeMode.LOCAL), manifest.runtimeModes());
        var provider = manifest.providers().get(0);
        assertEquals("example.paddleocr.document-parser", provider.id());
        assertEquals(ProviderManifest.Capability.DOCUMENT_PARSER, provider.capability());
        assertEquals("zh-CN", provider.documentParsing().defaultOptions().language());
        assertFalse(provider.documentParsing().defaultOptions().recognizeTables());
        assertTrue(provider.documentParsing().supportsTables());
        assertTrue(provider.documentParsing().defaultOptions().recognizeFormulas());
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
    }

    @Test
    void descriptionMayBeOmittedButRequiredConfigurationCannot() throws Exception {
        var node = example();
        node.remove("description");
        assertEquals("", read(node).description());
    }

    @Test
    void acceptsMultipleProviderDeclarationsWithoutConflatingPackageAndImplementationVersion() throws Exception {
        var node = example();
        var second = provider(node).deepCopy();
        second.put("id", "example.second-parser");
        second.put("version", "2.0.0");
        node.withArray("providers").add(second);
        var manifest = read(node);
        assertEquals("0.1.0-example", manifest.version());
        assertEquals("2.0.0", manifest.providers().get(1).version());
    }

    @Test
    void providerWithoutLanguageSelectionOrRecognitionFeaturesCanBeDeclared() throws Exception {
        var node = example();
        doc(node).putArray("languages");
        defaults(node).putNull("language");
        doc(node).put("supportsTables", false).put("supportsFormulas", false);
        defaults(node).put("recognizeTables", false).put("recognizeFormulas", false);
        var declaration = read(node).providers().get(0).documentParsing();
        assertTrue(declaration.languages().isEmpty());
        assertNull(declaration.defaultOptions().language());
    }

    @Test
    void parsedCollectionsCannotBeMutated() throws Exception {
        var manifest = read(example());
        assertThrows(UnsupportedOperationException.class, () -> manifest.providers().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.runtimeModes().clear());
        var declaration = manifest.providers().get(0).documentParsing();
        assertThrows(UnsupportedOperationException.class, () -> declaration.mediaTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> declaration.languages().clear());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDeclarations")
    void rejectsInvalidOrContradictoryDeclarations(String name, Consumer<ObjectNode> mutation) throws Exception {
        var node = example();
        mutation.accept(node);
        assertEquals(PluginManifestException.Code.INVALID_MANIFEST,
                assertThrows(PluginManifestException.class, () -> read(node)).code());
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                bad("missing id", n -> n.remove("id")),
                bad("invalid plugin id", n -> n.put("id", "../plugin")),
                bad("blank name", n -> n.put("name", " ")),
                bad("long name", n -> n.put("name", "n".repeat(129))),
                bad("long description", n -> n.put("description", "d".repeat(4097))),
                bad("null description", n -> n.putNull("description")),
                bad("blank package version", n -> n.put("version", "")),
                bad("unknown top field", n -> n.put("enabled", true)),
                bad("unknown provider field", n -> provider(n).put("className", "untrusted.Type")),
                bad("unknown declaration field", n -> doc(n).put("extra", true)),
                bad("unknown defaults field", n -> defaults(n).put("recognizeFormula", true)),
                bad("unknown capability", n -> provider(n).put("capability", "email")),
                bad("unknown runtime", n -> n.putArray("runtimeModes").add("SHELL")),
                bad("duplicate runtime", n -> n.putArray("runtimeModes").add("LOCAL").add("LOCAL")),
                bad("empty runtime", n -> n.putArray("runtimeModes")),
                bad("empty providers", n -> n.putArray("providers")),
                bad("null provider", n -> n.withArray("providers").addNull()),
                bad("duplicate provider id with different version", n -> {
                    var duplicate = provider(n).deepCopy();
                    duplicate.put("version", "different");
                    n.withArray("providers").add(duplicate);
                }),
                bad("too many providers", n -> {
                    var value = provider(n).deepCopy();
                    var values = n.putArray("providers");
                    for (int i = 0; i < 65; i++) values.add(value.deepCopy().put("id", "p" + i));
                }),
                bad("missing parsing declaration", n -> provider(n).remove("documentParsing")),
                bad("empty media types", n -> doc(n).putArray("mediaTypes")),
                bad("duplicate media", n -> doc(n).withArray("mediaTypes").add("application/pdf")),
                bad("noncanonical media", n -> doc(n).putArray("mediaTypes").add("Application/PDF")),
                bad("wildcard media", n -> doc(n).putArray("mediaTypes").add("image/*")),
                bad("null media", n -> doc(n).withArray("mediaTypes").addNull()),
                bad("duplicate language", n -> doc(n).withArray("languages").add("en")),
                bad("noncanonical language", n -> doc(n).putArray("languages").add("zh-cn")),
                bad("default language not supported", n -> defaults(n).put("language", "ja")),
                bad("malformed default language", n -> defaults(n).put("language", "zh_CN")),
                bad("noncanonical default language", n -> defaults(n).put("language", "zh-cn")),
                bad("missing default language", n -> defaults(n).remove("language")),
                bad("missing default options", n -> doc(n).remove("defaultOptions")),
                bad("null default flag", n -> defaults(n).putNull("recognizeTables")),
                bad("string boolean", n -> defaults(n).put("recognizeFormulas", "true")),
                bad("integer boolean", n -> doc(n).put("supportsTables", 1)),
                bad("unsupported formula enabled", n -> doc(n).put("supportsFormulas", false)),
                bad("unsupported tables enabled", n -> {
                    doc(n).put("supportsTables", false);
                    defaults(n).put("recognizeTables", true);
                }),
                bad("string schema version", n -> n.put("schemaVersion", "1")),
                bad("fractional schema version", n -> n.put("schemaVersion", 1.0)),
                bad("string SPI version", n -> n.put("spiVersion", "1"))
        );
    }

    @Test
    void distinguishesUnsupportedSchemaFromIncompatibleSpi() throws Exception {
        var node = example();
        node.put("schemaVersion", 2).put("newField", true);
        assertEquals(PluginManifestException.Code.UNSUPPORTED_SCHEMA_VERSION,
                assertThrows(PluginManifestException.class, () -> read(node)).code());
        var spi = example();
        spi.put("spiVersion", PluginSpi.VERSION + 1);
        assertEquals(PluginManifestException.Code.INCOMPATIBLE_SPI_VERSION,
                assertThrows(PluginManifestException.class, () -> read(spi)).code());
    }

    @Test
    void duplicateKeysAndTrailingDocumentsAreRejected() throws Exception {
        String valid = JSON.writeValueAsString(example());
        assertCode(PluginManifestException.Code.INVALID_JSON, valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        assertCode(PluginManifestException.Code.INVALID_JSON, valid.replace("\"recognizeTables\":false", "\"recognizeTables\":false,\"recognizeTables\":true"));
        assertCode(PluginManifestException.Code.INVALID_JSON, valid + " {}");
        assertCode(PluginManifestException.Code.INVALID_JSON, "{broken}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "true", "42"})
    void rootMustBeAnObject(String json) {
        assertCode(PluginManifestException.Code.INVALID_MANIFEST, json);
    }

    @Test
    void acceptsExactlyTheByteLimitButRejectsOneMoreByteWithoutReadingRest() throws Exception {
        byte[] json = JSON.writeValueAsBytes(example());
        byte[] exact = Arrays.copyOf(json, PluginManifestReader.MAX_BYTES);
        Arrays.fill(exact, json.length, exact.length, (byte) ' ');
        assertEquals("example.paddleocr", reader.read(new ByteArrayInputStream(exact)).id());
        byte[] large = Arrays.copyOf(exact, exact.length + 4096);
        var input = new TrackedInput(large);
        assertEquals(PluginManifestException.Code.TOO_LARGE,
                assertThrows(PluginManifestException.class, () -> reader.read(input)).code());
        assertEquals(PluginManifestReader.MAX_BYTES + 1, input.consumed());
        assertFalse(input.closed);
    }

    @Test
    void readerDoesNotCloseCallerOwnedStreamOnSuccessOrFailure() throws Exception {
        var success = new TrackedInput(JSON.writeValueAsBytes(example()));
        reader.read(success);
        assertFalse(success.closed);
        var invalid = new TrackedInput("{broken}".getBytes(StandardCharsets.UTF_8));
        assertThrows(PluginManifestException.class, () -> reader.read(invalid));
        assertFalse(invalid.closed);
    }

    @Test
    void rejectsExcessiveNestingAndHugeSingleStrings() {
        assertCode(PluginManifestException.Code.INVALID_JSON, "[".repeat(30) + "0" + "]".repeat(30));
        assertCode(PluginManifestException.Code.INVALID_JSON, "{\"name\":\"" + "x".repeat(20000) + "\"}");
    }

    @Test
    void readFailureHasSafeMessageAndRetainsCause() {
        var cause = new IOException("private file detail");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw cause; }
        };
        var ex = assertThrows(PluginManifestException.class, () -> reader.read(broken));
        assertEquals(PluginManifestException.Code.READ_FAILED, ex.code());
        assertSame(cause, ex.getCause());
        assertFalse(ex.getMessage().contains("private"));
    }

    @Test
    void unknownFieldErrorDoesNotEchoUntrustedKeyOrValue() throws Exception {
        var node = example();
        node.put("private-credential", "secret-value");
        var ex = assertThrows(PluginManifestException.class, () -> read(node));
        assertFalse(ex.getMessage().contains("private-credential"));
        assertFalse(ex.getMessage().contains("secret-value"));
    }

    private PluginManifest read(ObjectNode node) throws IOException {
        return reader.read(new ByteArrayInputStream(JSON.writeValueAsBytes(node)));
    }

    private void assertCode(PluginManifestException.Code code, String value) {
        assertEquals(code, assertThrows(PluginManifestException.class,
                () -> reader.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)))).code());
    }

    private static Arguments bad(String name, Consumer<ObjectNode> mutation) { return Arguments.of(name, mutation); }
    private static ObjectNode provider(ObjectNode root) { return (ObjectNode) root.get("providers").get(0); }
    private static ObjectNode doc(ObjectNode root) { return (ObjectNode) provider(root).get("documentParsing"); }
    private static ObjectNode defaults(ObjectNode root) { return (ObjectNode) doc(root).get("defaultOptions"); }

    private static ObjectNode example() throws IOException {
        try (var input = PluginManifestReaderTest.class.getResourceAsStream("/examples/plugin-manifest.paddleocr.example.json")) {
            assertNotNull(input, "the published example must be packaged as a resource");
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        int consumed() { return pos; }
        @Override public void close() { closed = true; }
    }
}
