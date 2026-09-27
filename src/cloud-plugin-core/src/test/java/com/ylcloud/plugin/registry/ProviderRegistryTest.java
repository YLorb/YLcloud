package com.ylcloud.plugin.registry;

import com.ylcloud.plugin.spi.PluginProvider;
import com.ylcloud.plugin.spi.ProviderCallContext;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import com.ylcloud.plugin.spi.document.DocumentParseRequest;
import com.ylcloud.plugin.spi.document.DocumentParseResult;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {
    private final ProviderRegistry registry = new ProviderRegistry();

    @Test
    void registerThenFindReturnsTypedInstanceWithoutCallingItsBusinessMethod() {
        var provider = new Parser("parser.a", Set.of("text/plain"));
        var registration = registry.register(DocumentParserProvider.class, provider);
        assertSame(provider, registry.require(DocumentParserProvider.class, "parser.a"));
        assertSame(provider, registry.find(DocumentParserProvider.class, "parser.a").orElseThrow());
        assertEquals("1.0.0", registration.descriptor().version());
        assertEquals(DocumentParserProvider.class, registration.capability());
        assertEquals(0, provider.parseCalls);
    }

    @Test
    void callerCanInvokeProviderFoundInRegistryWithOriginalRequestAndContext() throws Exception {
        var provider = new Parser("parser.a", Set.of("text/plain"));
        registry.register(DocumentParserProvider.class, provider);
        var request = new DocumentParseRequest("snapshot", "sample.txt", "text/plain",
                () -> new ByteArrayInputStream(new byte[0]), 10, 1);
        var context = new ProviderCallContext("call", Instant.now().plusSeconds(30));
        var result = registry.require(DocumentParserProvider.class, "parser.a").parse(request, context);
        assertEquals("test response", result.fullText());
        assertSame(request, provider.lastRequest);
        assertSame(context, provider.lastContext);
        assertEquals(1, provider.parseCalls);
    }

    @Test
    void unknownIdDoesNotFallBackToAnotherProvider() {
        registry.register(DocumentParserProvider.class, new Parser("parser.a", Set.of("text/plain")));
        assertTrue(registry.find(DocumentParserProvider.class, "missing").isEmpty());
        assertCode(ProviderRegistryException.Code.NOT_FOUND,
                () -> registry.require(DocumentParserProvider.class, "missing"));
    }

    @Test
    void duplicateIdDoesNotOverwriteExistingInstanceEvenForDifferentVersion() {
        var original = new Parser("parser.a", Set.of("text/plain"));
        registry.register(DocumentParserProvider.class, original);
        assertCode(ProviderRegistryException.Code.DUPLICATE_ID,
                () -> registry.register(DocumentParserProvider.class, original));
        var replacement = new Parser("parser.a", Set.of("application/pdf"));
        replacement.descriptor = new ProviderDescriptor("parser.a", "2.0.0");
        assertCode(ProviderRegistryException.Code.DUPLICATE_ID,
                () -> registry.register(DocumentParserProvider.class, replacement));
        assertSame(original, registry.require(DocumentParserProvider.class, "parser.a"));
        assertTrue(registry.documentParsersFor("application/pdf").isEmpty());
    }

    @Test
    void idIsUniqueAcrossCapabilities() {
        registry.register(DocumentParserProvider.class, new Parser("shared", Set.of("text/plain")));
        GreetingProvider greeting = () -> new ProviderDescriptor("shared", "1");
        assertCode(ProviderRegistryException.Code.DUPLICATE_ID,
                () -> registry.register(GreetingProvider.class, greeting));
    }

    @Test
    void futureCapabilityCanRegisterWithoutDocumentMetadata() {
        GreetingProvider greeting = () -> new ProviderDescriptor("greeting", "1");
        var registration = registry.register(GreetingProvider.class, greeting);
        assertSame(greeting, registry.require(GreetingProvider.class, "greeting"));
        assertTrue(registration.documentMediaTypes().isEmpty());
        assertTrue(registry.documentParsersFor("text/plain").isEmpty());
    }

    @Test
    void incidentalInterfaceIsNotPublishedAndMismatchIsDistinctFromMissing() {
        var provider = new DualProvider();
        registry.register(DocumentParserProvider.class, provider);
        assertTrue(registry.find(GreetingProvider.class, "dual").isEmpty());
        assertCode(ProviderRegistryException.Code.CAPABILITY_MISMATCH,
                () -> registry.require(GreetingProvider.class, "dual"));
        assertTrue(registry.list(GreetingProvider.class).isEmpty());
    }

    @Test
    void explicitlyPublishedSubinterfaceCanBeViewedAsItsParentCapability() {
        var provider = new SpecializedParser();
        registry.register(SpecializedDocumentParser.class, provider);
        assertSame(provider, registry.require(DocumentParserProvider.class, "specialized"));
        assertEquals(1, registry.documentParsersFor("application/pdf").size());
        assertEquals(1, registry.list(DocumentParserProvider.class).size());
    }

    @Test
    void baseCapabilityDoesNotAutomaticallyPublishItsChildInterface() {
        registry.register(DocumentParserProvider.class, new SpecializedParser());
        assertCode(ProviderRegistryException.Code.CAPABILITY_MISMATCH,
                () -> registry.require(SpecializedDocumentParser.class, "specialized"));
    }

    @Test
    void metadataIsCapturedOnceAndLaterChangesDoNotAlterRouting() {
        var mutableTypes = new HashSet<>(Set.of("application/pdf"));
        var provider = new Parser("parser.a", mutableTypes);
        var registration = registry.register(DocumentParserProvider.class, provider);
        mutableTypes.clear();
        mutableTypes.add("image/png");
        provider.descriptor = new ProviderDescriptor("changed", "9");
        assertEquals(List.of(new ProviderDescriptor("parser.a", "1.0.0")), registry.list(DocumentParserProvider.class));
        assertEquals(1, registry.documentParsersFor("application/pdf").size());
        assertTrue(registry.documentParsersFor("image/png").isEmpty());
        assertSame(provider, registry.require(DocumentParserProvider.class, "parser.a"));
        assertEquals(1, provider.descriptorReads);
        assertEquals(1, provider.mediaReads);
        assertThrows(UnsupportedOperationException.class, () -> registration.documentMediaTypes().clear());
    }

    @Test
    void listsAreSortedImmutableSnapshotsAndMultipleCandidatesAreAllowed() {
        registry.register(DocumentParserProvider.class, new Parser("z.parser", Set.of("application/pdf")));
        var first = registry.register(DocumentParserProvider.class, new Parser("a.parser", Set.of("application/pdf")));
        var snapshot = registry.documentParsersFor(" Application/PDF ");
        assertEquals(List.of("a.parser", "z.parser"), snapshot.stream().map(ProviderDescriptor::id).toList());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> registry.list(DocumentParserProvider.class).clear());
        assertTrue(registry.unregister(first));
        assertEquals(2, snapshot.size());
        assertEquals(1, registry.documentParsersFor("application/pdf").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLICATION/PDF", " text/plain", "application/*", "text/plain;charset=utf-8", "", "text"})
    void invalidMetadataNeverPublishesAnEntry(String type) {
        assertCode(ProviderRegistryException.Code.INVALID_METADATA,
                () -> registry.register(DocumentParserProvider.class, new Parser("invalid", Set.of(type))));
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
        assertTrue(registry.find(DocumentParserProvider.class, "invalid").isEmpty());
    }

    @Test
    void nullEmptyAndNullElementMediaDeclarationsAreRejected() {
        var nullEntry = new HashSet<String>();
        nullEntry.add(null);
        for (Set<String> values : java.util.Arrays.asList(null, Set.<String>of(), nullEntry)) {
            assertCode(ProviderRegistryException.Code.INVALID_METADATA,
                    () -> registry.register(DocumentParserProvider.class, new Parser("invalid", values)));
        }
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
    }

    @Test
    void missingDescriptorIsRejectedWithoutDisturbingExistingEntries() {
        var good = new Parser("good", Set.of("text/plain"));
        registry.register(DocumentParserProvider.class, good);
        var bad = new Parser("bad", Set.of("text/plain"));
        bad.descriptor = null;
        assertCode(ProviderRegistryException.Code.INVALID_METADATA,
                () -> registry.register(DocumentParserProvider.class, bad));
        assertEquals(1, registry.list(DocumentParserProvider.class).size());
        assertSame(good, registry.require(DocumentParserProvider.class, "good"));
    }

    @Test
    void metadataCallbackFailureUsesSafeMessageAndKeepsCause() {
        var cause = new IllegalStateException("private engine detail");
        GreetingProvider broken = () -> { throw cause; };
        var ex = assertThrows(ProviderRegistryException.class,
                () -> registry.register(GreetingProvider.class, broken));
        assertEquals(ProviderRegistryException.Code.INVALID_METADATA, ex.code());
        assertSame(cause, ex.getCause());
        assertFalse(ex.getMessage().contains("private"));
        assertTrue(registry.list(GreetingProvider.class).isEmpty());
    }

    @Test
    void registrationUsesExplicitBusinessInterfaceAndActualInstanceMustMatch() {
        var parser = new Parser("parser", Set.of("text/plain"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(PluginProvider.class, parser));
        assertThrows(IllegalArgumentException.class, () -> registry.register(Parser.class, parser));
        assertThrows(NullPointerException.class, () -> registry.register(DocumentParserProvider.class, null));
        assertThrows(IllegalArgumentException.class, () -> registerWithRawType(GreetingProvider.class, parser));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerWithRawType(Class capability, PluginProvider provider) {
        registry.register(capability, provider);
    }

    @Test
    void invalidQueriesAreRejectedWithoutChangingRegistry() {
        assertThrows(IllegalArgumentException.class, () -> registry.require(DocumentParserProvider.class, " "));
        assertThrows(NullPointerException.class, () -> registry.find(DocumentParserProvider.class, null));
        assertThrows(IllegalArgumentException.class, () -> registry.documentParsersFor("application/*"));
        assertThrows(NullPointerException.class, () -> registry.documentParsersFor(null));
        assertThrows(IllegalArgumentException.class, () -> registry.list(PluginProvider.class));
    }

    @Test
    void unregisterRemovesRoutingButDoesNotRevokePreviouslyObtainedReference() {
        var provider = new Parser("parser", Set.of("text/plain"));
        var registration = registry.register(DocumentParserProvider.class, provider);
        var retained = registry.require(DocumentParserProvider.class, "parser");
        assertTrue(registry.unregister(registration));
        assertFalse(registry.unregister(registration));
        assertTrue(registry.find(DocumentParserProvider.class, "parser").isEmpty());
        assertTrue(registry.documentParsersFor("text/plain").isEmpty());
        assertSame(provider, retained);
        assertEquals(0, provider.parseCalls);
    }

    @Test
    void staleRegistrationCannotRemoveReplacementEvenWhenSameInstanceIsRegisteredAgain() {
        var provider = new Parser("parser", Set.of("text/plain"));
        var old = registry.register(DocumentParserProvider.class, provider);
        assertTrue(registry.unregister(old));
        var replacement = registry.register(DocumentParserProvider.class, provider);
        assertFalse(registry.unregister(old));
        assertSame(provider, registry.require(DocumentParserProvider.class, "parser"));
        assertTrue(registry.unregister(replacement));
    }

    @Test
    void anotherRegistryCannotUseRegistrationToRemoveItsOwnEntry() {
        var provider = new Parser("parser", Set.of("text/plain"));
        var foreign = new ProviderRegistry().register(DocumentParserProvider.class, provider);
        registry.register(DocumentParserProvider.class, provider);
        assertFalse(registry.unregister(foreign));
        assertSame(provider, registry.require(DocumentParserProvider.class, "parser"));
    }

    private static void assertCode(ProviderRegistryException.Code expected, Runnable action) {
        assertEquals(expected, assertThrows(ProviderRegistryException.class, action::run).code());
    }

    interface GreetingProvider extends PluginProvider {}
    interface SpecializedDocumentParser extends DocumentParserProvider {}

    static class Parser implements DocumentParserProvider {
        ProviderDescriptor descriptor;
        final Set<String> mediaTypes;
        int descriptorReads;
        int mediaReads;
        int parseCalls;
        DocumentParseRequest lastRequest;
        ProviderCallContext lastContext;

        Parser(String id, Set<String> mediaTypes) {
            this.descriptor = new ProviderDescriptor(id, "1.0.0");
            this.mediaTypes = mediaTypes;
        }

        @Override public ProviderDescriptor descriptor() { descriptorReads++; return descriptor; }
        @Override public Set<String> supportedMediaTypes() { mediaReads++; return mediaTypes; }
        @Override public DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context) {
            // 仅验证 Registry 返回正确实例及调用参数；没有模拟真实文档识别。
            parseCalls++;
            lastRequest = request;
            lastContext = context;
            return new DocumentParseResult("test response", List.of(), List.of());
        }
    }

    private static final class DualProvider extends Parser implements GreetingProvider {
        DualProvider() { super("dual", Set.of("text/plain")); }
    }

    private static final class SpecializedParser extends Parser implements SpecializedDocumentParser {
        SpecializedParser() { super("specialized", Set.of("application/pdf")); }
    }
}
