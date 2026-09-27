package com.ylcloud.plugin.manifest;

import com.ylcloud.plugin.spi.PluginSpi;
import com.ylcloud.plugin.spi.document.DocumentParseOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestValueTest {
    @Test
    void copiesAllNestedCollectionsEvenWhenConstructedWithoutJsonReader() {
        var media = new HashSet<>(Set.of("application/pdf"));
        var languages = new HashSet<>(Set.of("en"));
        var declaration = new DocumentParserDeclaration(media, languages, true, true,
                new DocumentParseOptions("en", false, true));
        var providers = new ArrayList<>(List.of(new ProviderManifest("parser", "1", ProviderManifest.Capability.DOCUMENT_PARSER, declaration)));
        var modes = new HashSet<>(Set.of(PluginManifest.RuntimeMode.LOCAL));
        var manifest = new PluginManifest(1, "plugin", "Plugin", "1", "", PluginSpi.VERSION, modes, providers);
        media.clear(); languages.clear(); providers.clear(); modes.clear();
        assertEquals(1, manifest.providers().size());
        assertEquals(Set.of(PluginManifest.RuntimeMode.LOCAL), manifest.runtimeModes());
        assertEquals(Set.of("application/pdf"), declaration.mediaTypes());
        assertEquals(Set.of("en"), declaration.languages());
    }

    @Test
    void directConstructionCannotBypassDefaultConsistencyChecks() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentParserDeclaration(Set.of("application/pdf"), Set.of("en"),
                false, false, new DocumentParseOptions("en", false, true)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentParserDeclaration(Set.of("application/pdf"), Set.of("en"),
                true, true, DocumentParseOptions.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentParserDeclaration(Set.of("application/pdf"), Set.of("en"),
                true, true, new DocumentParseOptions("ja", false, false)));
    }

    @Test
    void directConstructionCannotBypassDuplicateIdsOrCompatibility() {
        var declaration = new DocumentParserDeclaration(Set.of("text/plain"), Set.of(), false, false,
                new DocumentParseOptions(null, false, false));
        var provider = new ProviderManifest("parser", "1", ProviderManifest.Capability.DOCUMENT_PARSER, declaration);
        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(1, "plugin", "Plugin", "1", "", PluginSpi.VERSION,
                Set.of(PluginManifest.RuntimeMode.LOCAL), List.of(provider, provider)));
        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(2, "plugin", "Plugin", "1", "", PluginSpi.VERSION,
                Set.of(PluginManifest.RuntimeMode.LOCAL), List.of(provider)));
    }
}
