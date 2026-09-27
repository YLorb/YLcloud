package com.ylcloud.plugin.manager;

import com.ylcloud.plugin.manifest.*;
import com.ylcloud.plugin.registry.*;
import com.ylcloud.plugin.spi.*;
import com.ylcloud.plugin.spi.document.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ylcloud.plugin.manager.PluginManagerException.Code.*;
import static com.ylcloud.plugin.manifest.PluginManifest.RuntimeMode.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {
    private final ProviderRegistry registry = new ProviderRegistry();
    private final PluginManager manager = new PluginManager(registry);

    @Test void lifecyclePublishesAndRemovesWholePackage() {
        var initial = manager.install(manifest("plugin", "one", "two"));
        assertEquals(PluginManager.State.DISABLED, initial.state());
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
        var first = parser("one");
        var enabled = manager.enable("plugin", LOCAL, Map.of("one", first, "two", parser("two")));
        assertEquals(PluginManager.State.ENABLED, enabled.state());
        assertEquals(LOCAL, enabled.mode());
        assertEquals(2, registry.documentParsersFor("application/pdf").size());
        assertSame(first, registry.require(DocumentParserProvider.class, "one"));
        assertEquals(PluginManager.State.DISABLED, initial.state());
        manager.disable("plugin");
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
        assertNull(manager.get("plugin").mode());
        manager.uninstall("plugin");
        assertTrue(manager.list().isEmpty());
    }

    @Test void duplicateInstallDoesNotReplaceManifest() {
        var original = manifest("plugin", "one");
        manager.install(original);
        code(ALREADY_INSTALLED, () -> manager.install(manifest("plugin", "two")));
        assertEquals(original, manager.get("plugin").manifest());
    }

    @Test void disabledPackagesReserveIdsUntilUninstall() {
        manager.install(manifest("first", "one"));
        code(PROVIDER_ID_CONFLICT, () -> manager.install(manifest("second", "two", "one")));
        assertEquals(1, manager.list().size());
        manager.install(manifest("third", "two")); // 失败的安装没有残留归属。
        manager.uninstall("first");
        manager.install(manifest("replacement", "one"));
    }

    @Test void enabledPluginCannotBeUninstalledOrEnabledTwice() {
        manager.install(manifest("plugin", "one"));
        manager.enable("plugin", LOCAL, Map.of("one", parser("one")));
        code(INVALID_STATE, () -> manager.uninstall("plugin"));
        code(INVALID_STATE, () -> manager.enable("plugin", LOCAL, Map.of("one", parser("one"))));
        assertEquals(1, registry.list(DocumentParserProvider.class).size());
    }

    @Test void unsupportedModeFailsBeforeMetadataCallback() {
        manager.install(manifest("plugin", "one"));
        code(UNSUPPORTED_RUNTIME, () -> manager.enable("plugin", DOCKER,
                Map.of("one", new Parser("one", "1", Set.of("application/pdf"),
                        () -> fail("metadata must not execute")))));
    }

    @Test void missingOrExtraProviderRejected() {
        manager.install(manifest("plugin", "one"));
        code(MANIFEST_MISMATCH, () -> manager.enable("plugin", LOCAL, Map.of()));
        code(MANIFEST_MISMATCH, () -> manager.enable("plugin", LOCAL,
                Map.of("one", parser("one"), "extra", parser("extra"))));
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
    }

    @Test void identityVersionMimeAndCapabilityMustMatch() {
        manager.install(manifest("plugin", "one"));
        List<PluginProvider> bad = List.of(parser("wrong"),
                new Parser("one", "2", Set.of("application/pdf"), () -> {}),
                new Parser("one", "1", Set.of("text/plain"), () -> {}),
                (PluginProvider) () -> new ProviderDescriptor("one", "1"));
        for (var provider : bad) {
            code(MANIFEST_MISMATCH, () -> manager.enable("plugin", LOCAL, Map.of("one", provider)));
        }
        assertEquals(PluginManager.State.DISABLED, manager.get("plugin").state());
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
    }

    @Test void metadataFailureDoesNotPublishEarlierProviderAndCanRetry() {
        manager.install(manifest("plugin", "one", "two"));
        var broken = new Parser("two", "1", Set.of("application/pdf"),
                () -> { throw new IllegalStateException("private-engine-detail"); });
        var error = assertThrows(ProviderRegistryException.class,
                () -> manager.enable("plugin", LOCAL, Map.of("one", parser("one"), "two", broken)));
        assertFalse(error.getMessage().contains("private-engine-detail"));
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
        assertEquals(0, manager.get("plugin").revision());
        manager.enable("plugin", LOCAL, Map.of("one", parser("one"), "two", parser("two")));
    }

    @Test void registryConflictDoesNotPartiallyPublishOrAlterOtherOwner() {
        manager.install(manifest("plugin", "one", "two"));
        var external = parser("two");
        var token = registry.register(DocumentParserProvider.class, external);
        assertThrows(ProviderRegistryException.class,
                () -> manager.enable("plugin", LOCAL, Map.of("one", parser("one"), "two", parser("two"))));
        assertTrue(registry.find(DocumentParserProvider.class, "one").isEmpty());
        assertSame(external, registry.require(DocumentParserProvider.class, "two"));
        assertEquals(PluginManager.State.DISABLED, manager.get("plugin").state());
        registry.unregister(token);
        manager.enable("plugin", LOCAL, Map.of("one", parser("one"), "two", parser("two")));
    }

    @Test void reenableUsesNewInstancesAndDisableDoesNotStopRetainedReference() throws Exception {
        manager.install(manifest("plugin", "one"));
        manager.enable("plugin", LOCAL, Map.of("one", parser("one")));
        var retained = registry.require(DocumentParserProvider.class, "one");
        manager.disable("plugin");
        assertNotNull(retained.parse(null, null)); // 测试桩证明只是撤销名册，没有运行时停止屏障。
        var next = parser("one");
        manager.enable("plugin", LOCAL, Map.of("one", next));
        assertSame(next, registry.require(DocumentParserProvider.class, "one"));
        assertNotSame(retained, next);
        assertEquals(3, manager.get("plugin").revision());
    }

    @Test void snapshotsAreSortedImmutableAndMissingPluginFails() {
        manager.install(manifest("z", "z.provider"));
        manager.install(manifest("a", "a.provider"));
        var list = manager.list();
        assertEquals(List.of("a", "z"), list.stream().map(s -> s.manifest().id()).toList());
        assertThrows(UnsupportedOperationException.class, list::clear);
        manager.uninstall("a");
        assertEquals(2, list.size());
        code(NOT_INSTALLED, () -> manager.get("missing"));
        code(NOT_INSTALLED, () -> manager.disable("missing"));
        code(NOT_INSTALLED, () -> manager.uninstall("missing"));
    }

    @Test void disableCancelsInFlightEnableWithoutWaitingForPluginCallback() throws Exception {
        staleEnable(false);
    }

    @Test void uninstallAndReinstallSameIdRejectsOldEnable() throws Exception {
        staleEnable(true);
    }

    private void staleEnable(boolean replace) throws Exception {
        manager.install(manifest("plugin", "one"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var provider = new Parser("one", "1", Set.of("application/pdf"), () -> {
                entered.countDown();
                await(release);
            });
            var result = executor.submit(() -> manager.enable("plugin", LOCAL, Map.of("one", provider)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.disable("plugin");
            if (replace) {
                manager.uninstall("plugin");
                manager.install(manifest("plugin", "one"));
            }
            release.countDown();
            var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(CONCURRENT_CHANGE, ((PluginManagerException) error.getCause()).code());
            assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
            assertEquals(PluginManager.State.DISABLED, manager.get("plugin").state());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void concurrentEnableHasExactlyOneWinnerAndMetadataIsReadOnce() throws Exception {
        manager.install(manifest("plugin", "one"));
        var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        var provider = new Parser("one", "1", Set.of("application/pdf"), () -> {
            calls.incrementAndGet();
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        var executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> enable = () -> {
            try { manager.enable("plugin", LOCAL, Map.of("one", provider)); return true; }
            catch (PluginManagerException e) { assertEquals(CONCURRENT_CHANGE, e.code()); return false; }
        };
        try {
            var first = executor.submit(enable);
            var second = executor.submit(enable);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals(1, registry.list(DocumentParserProvider.class).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static void code(PluginManagerException.Code code, Runnable action) {
        assertEquals(code, assertThrows(PluginManagerException.class, action::run).code());
    }

    private static Parser parser(String id) { return new Parser(id, "1", Set.of("application/pdf"), () -> {}); }

    private static PluginManifest manifest(String id, String... providerIds) {
        var declaration = new DocumentParserDeclaration(Set.of("application/pdf"), Set.of("zh-CN"),
                true, true, new DocumentParseOptions("zh-CN", false, true));
        return new PluginManifest(1, id, id, "1", "", 1, Set.of(LOCAL),
                Arrays.stream(providerIds).map(p -> new ProviderManifest(p, "1",
                        ProviderManifest.Capability.DOCUMENT_PARSER, declaration)).toList());
    }

    private record Parser(String id, String version, Set<String> supportedMediaTypes,
                          Runnable onDescriptor) implements DocumentParserProvider {
        public ProviderDescriptor descriptor() { onDescriptor.run(); return new ProviderDescriptor(id, version); }
        public DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context) {
            return new DocumentParseResult("", List.of(), List.of());
        }
    }
}
