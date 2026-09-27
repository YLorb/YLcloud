package com.ylcloud.plugin.registry;

import com.ylcloud.plugin.spi.PluginProvider;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryConcurrencyTest {
    private static final int WORKERS = 8;

    @Test
    void concurrentSameIdHasExactlyOneWinner() throws Exception {
        var registry = new ProviderRegistry();
        var barrier = new CyclicBarrier(WORKERS);
        var jobs = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < WORKERS; i++) {
            jobs.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    registry.register(TestCapability.class, () -> new ProviderDescriptor("same", "1"));
                    return true;
                } catch (ProviderRegistryException ex) {
                    assertEquals(ProviderRegistryException.Code.DUPLICATE_ID, ex.code());
                    return false;
                }
            });
        }
        assertEquals(1L, parallel(jobs).stream().filter(Boolean::booleanValue).count());
        assertEquals(1, registry.list(TestCapability.class).size());
    }

    @Test
    void concurrentDifferentIdsAreAllPreserved() throws Exception {
        var registry = new ProviderRegistry();
        var barrier = new CyclicBarrier(WORKERS);
        var jobs = new ArrayList<Callable<ProviderRegistry.Registration>>();
        for (int i = 0; i < WORKERS; i++) {
            String id = "provider." + i;
            jobs.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return registry.register(TestCapability.class, () -> new ProviderDescriptor(id, "1"));
            });
        }
        assertEquals(WORKERS, parallel(jobs).size());
        assertEquals(IntStream.range(0, WORKERS).mapToObj(i -> "provider." + i).toList(),
                registry.list(TestCapability.class).stream().map(ProviderDescriptor::id).toList());
    }

    @Test
    void concurrentRemovalOfOneRegistrationHasExactlyOneWinner() throws Exception {
        var registry = new ProviderRegistry();
        var registration = registry.register(TestCapability.class, () -> new ProviderDescriptor("same", "1"));
        var barrier = new CyclicBarrier(WORKERS);
        var jobs = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < WORKERS; i++) {
            jobs.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return registry.unregister(registration);
            });
        }
        assertEquals(1L, parallel(jobs).stream().filter(Boolean::booleanValue).count());
        assertTrue(registry.list(TestCapability.class).isEmpty());
    }

    private static <T> List<T> parallel(List<Callable<T>> jobs) throws Exception {
        var executor = Executors.newFixedThreadPool(WORKERS);
        try {
            var futures = new ArrayList<Future<T>>();
            for (var job : jobs) {
                futures.add(executor.submit(job));
            }
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private interface TestCapability extends PluginProvider {}
}
