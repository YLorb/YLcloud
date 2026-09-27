package com.ylcloud.plugin.registry;

import com.ylcloud.plugin.spi.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryBatchTest {
    private interface Capability extends PluginProvider { }
    private static ProviderRegistry.PreparedRegistration prepare(ProviderRegistry registry, String id) {
        return registry.prepare(Capability.class, () -> new ProviderDescriptor(id, "1"));
    }

    @Test void preparationIsInvisibleAndForeignPreparationRejected() {
        var registry = new ProviderRegistry();
        var prepared = prepare(registry, "one");
        assertTrue(registry.list(Capability.class).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProviderRegistry().registerAll(List.of(prepared)));
    }

    @Test void internalDuplicatesAndExistingConflictsPublishNothing() {
        var registry = new ProviderRegistry();
        var one = prepare(registry, "one");
        assertThrows(ProviderRegistryException.class, () -> registry.registerAll(List.of(one, one)));
        assertTrue(registry.list(Capability.class).isEmpty());
        registry.registerAll(List.of(one));
        assertThrows(ProviderRegistryException.class,
                () -> registry.registerAll(List.of(prepare(registry, "two"), one)));
        assertEquals(List.of("one"), registry.list(Capability.class).stream().map(ProviderDescriptor::id).toList());
    }

    @Test void republishingPreparedSnapshotCreatesNewTokensAndStaleRemovalPreservesThem() {
        var registry = new ProviderRegistry();
        var prepared = prepare(registry, "one");
        var first = registry.registerAll(List.of(prepared));
        assertEquals(1, registry.unregisterAll(first));
        var second = registry.registerAll(List.of(prepared));
        assertNotSame(first.get(0), second.get(0));
        assertEquals(0, registry.unregisterAll(first));
        assertTrue(registry.find(Capability.class, "one").isPresent());
    }

    @Test void removalSkipsStaleTokenButRemovesOtherOwnedEntries() {
        var registry = new ProviderRegistry();
        var tokens = registry.registerAll(List.of(prepare(registry, "one"), prepare(registry, "two")));
        registry.unregister(tokens.get(0));
        registry.registerAll(List.of(prepare(registry, "one")));
        assertEquals(1, registry.unregisterAll(tokens));
        assertEquals(List.of("one"), registry.list(Capability.class).stream().map(ProviderDescriptor::id).toList());
        assertEquals(0, new ProviderRegistry().unregisterAll(tokens));
    }

    @Test void concurrentReaderNeverSeesPartOfBatch() throws Exception {
        var registry = new ProviderRegistry();
        var prepared = List.of(prepare(registry, "one"), prepare(registry, "two"));
        var running = new AtomicBoolean(true);
        var started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var reader = executor.submit(() -> {
                started.countDown();
                do {
                    int size = registry.list(Capability.class).size();
                    assertTrue(size == 0 || size == 2, "partially published package");
                } while (running.get());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                var tokens = registry.registerAll(prepared);
                assertEquals(2, registry.unregisterAll(tokens));
            }
            running.set(false);
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            running.set(false);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
