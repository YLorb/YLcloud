package com.ylcloud.plugin.runtime;

import com.ylcloud.plugin.manager.*;
import com.ylcloud.plugin.manifest.*;
import com.ylcloud.plugin.registry.*;
import com.ylcloud.plugin.spi.*;
import com.ylcloud.plugin.spi.document.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.ylcloud.plugin.manifest.PluginManifest.RuntimeMode.*;

class PluginRuntimeTest {
    private static final PluginRuntime.Limits LIMITS = new PluginRuntime.Limits(1, 1);
    private static final DocumentParseResult EMPTY = new DocumentParseResult("", List.of(), List.of());

    @Test void managedCallRunsOnWorkerAndResolvesDefaultsAndExplicitFalse() throws Exception {
        var seen = new AtomicReference<DocumentParseRequest>();
        var thread = new AtomicReference<Thread>();
        var fixture = new Fixture((request, context) -> { seen.set(request); thread.set(Thread.currentThread()); return EMPTY; });
        try {
            fixture.enable(LOCAL);
            var provider = fixture.provider();
            assertSame(EMPTY, provider.parse(request(DocumentParseOptions.defaults()), context()));
            assertNotSame(Thread.currentThread(), thread.get());
            assertEquals(new DocumentParseOptions("zh-CN", false, true), seen.get().options());
            provider.parse(request(new DocumentParseOptions("en", true, false)), context());
            assertEquals(new DocumentParseOptions("en", true, false), seen.get().options());
        } finally { fixture.stop(); }
        assertEquals(1, fixture.closes.get());
    }

    @Test void retainedReferenceRejectsAfterDisableAndNewRunDoesNotReviveOldReference() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        fixture.enable(LOCAL);
        var old = fixture.provider();
        fixture.stop();
        assertEquals(ProviderException.Code.UNAVAILABLE,
                assertThrows(ProviderException.class, () -> old.parse(request(DocumentParseOptions.defaults()), context())).code());
        fixture.enable(LOCAL);
        try {
            assertSame(EMPTY, fixture.provider().parse(request(DocumentParseOptions.defaults()), context()));
            assertEquals(ProviderException.Code.UNAVAILABLE,
                    assertThrows(ProviderException.class, () -> old.parse(request(DocumentParseOptions.defaults()), context())).code());
        } finally { fixture.stop(); }
        assertEquals(2, fixture.closes.get());
    }

    @Test void unsupportedOptionsRejectedWithoutEnteringProvider() throws Exception {
        var calls = new AtomicInteger();
        var fixture = new Fixture((r, c) -> { calls.incrementAndGet(); return EMPTY; });
        fixture.enable(LOCAL);
        try {
            assertEquals(ProviderException.Code.UNSUPPORTED_OPTION,
                    assertThrows(ProviderException.class, () -> fixture.provider().parse(
                            request(new DocumentParseOptions("ja", null, null)), context())).code());
            assertEquals(0, calls.get());
        } finally { fixture.stop(); }
    }

    @Test void expiredCallNeverStartsAndUnexpectedFailureIsSanitized() throws Exception {
        var calls = new AtomicInteger();
        var fixture = new Fixture((r, c) -> { calls.incrementAndGet(); throw new IllegalStateException("private-token"); });
        fixture.enable(LOCAL);
        try {
            var request = request(DocumentParseOptions.defaults());
            assertEquals(ProviderException.Code.DEADLINE_EXCEEDED,
                    assertThrows(ProviderException.class, () -> fixture.provider().parse(request,
                            new ProviderCallContext("expired", Instant.now().minusSeconds(1)))).code());
            assertEquals(0, calls.get());
            var error = assertThrows(ProviderException.class, () -> fixture.provider().parse(request, context()));
            assertEquals(ProviderException.Code.INTERNAL_ERROR, error.code());
            assertFalse(error.getMessage().contains("private-token"));
        } finally { fixture.stop(); }
    }

    @Test void timeoutDoesNotReleaseResourcesWhileUncooperativeWorkerStillRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = new Fixture((r, c) -> {
            entered.countDown();
            // 模拟不响应中断的本地引擎；直到测试主动释放才能退出。
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return EMPTY;
        });
        var caller = Executors.newSingleThreadExecutor();
        fixture.enable(LOCAL);
        try {
            var call = caller.submit(() -> assertThrows(ProviderException.class,
                    () -> fixture.provider().parse(request(DocumentParseOptions.defaults()),
                            new ProviderCallContext("timeout", Instant.now().plusMillis(300)))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ProviderException.Code.DEADLINE_EXCEEDED, call.get(5, TimeUnit.SECONDS).code());
            fixture.manager.disable("plugin");
            assertFalse(fixture.manager.awaitStopped("plugin", Duration.ZERO));
            assertEquals(0, fixture.closes.get());
            assertEquals(PluginRuntime.State.STOPPING, fixture.manager.runtimeState("plugin"));
            assertThrows(PluginManagerException.class, () -> fixture.manager.uninstall("plugin"));
            assertThrows(PluginManagerException.class, () -> fixture.enable(LOCAL));
            release.countDown();
            assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
            assertEquals(1, fixture.closes.get());
        } finally {
            release.countDown();
            fixture.stop();
            shutdown(caller);
        }
    }

    @Test void callerInterruptionRequestsWorkerInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var fixture = new Fixture((r, c) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
            return EMPTY;
        });
        fixture.enable(LOCAL);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var call = caller.submit(() -> fixture.provider().parse(request(DocumentParseOptions.defaults()), context()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            call.cancel(true);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally { fixture.stop(); shutdown(caller); }
    }

    @Test void boundedQueueRejectsOverloadAndDrainsAcceptedTasks() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = new Fixture((r, c) -> { entered.countDown(); release.await(); return EMPTY; });
        fixture.enable(LOCAL);
        var caller = Executors.newFixedThreadPool(2);
        var secondThread = new AtomicReference<Thread>();
        try {
            var provider = fixture.provider();
            var first = caller.submit(() -> provider.parse(request(DocumentParseOptions.defaults()), context()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = caller.submit(() -> {
                secondThread.set(Thread.currentThread());
                return provider.parse(request(DocumentParseOptions.defaults()), context());
            });
            waitUntilWaiting(secondThread); // 第二个调用等待 Future，表明已成功进入唯一队列槽位。
            var error = assertThrows(ProviderException.class,
                    () -> provider.parse(request(DocumentParseOptions.defaults()), context()));
            assertEquals(ProviderException.Code.UNAVAILABLE, error.code());
            fixture.manager.disable("plugin");
            assertFalse(fixture.manager.awaitStopped("plugin", Duration.ZERO));
            release.countDown();
            assertSame(EMPTY, first.get(5, TimeUnit.SECONDS));
            assertSame(EMPTY, second.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        } finally { release.countDown(); fixture.stop(); shutdown(caller); }
    }

    @Test void cancelledQueuedCallNeverExecutesAndFreesQueueSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var fixture = new Fixture((r, c) -> { calls.incrementAndGet(); entered.countDown(); release.await(); return EMPTY; });
        fixture.enable(LOCAL);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var first = caller.submit(() -> fixture.provider().parse(request(DocumentParseOptions.defaults()), context()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ProviderException.Code.DEADLINE_EXCEEDED,
                    assertThrows(ProviderException.class, () -> fixture.provider().parse(request(DocumentParseOptions.defaults()),
                            new ProviderCallContext("queued", Instant.now().plusMillis(100)))).code());
            assertEquals(1, calls.get());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            fixture.provider().parse(request(DocumentParseOptions.defaults()), context());
            assertEquals(2, calls.get());
        } finally { release.countDown(); fixture.stop(); shutdown(caller); }
    }

    @Test void disableDuringStartPreventsPublicationAndClosesReturnedSession() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = new Fixture((r, c) -> EMPTY);
        var starter = Executors.newSingleThreadExecutor();
        try {
            var enabling = starter.submit(() -> fixture.manager.enableUnverified("plugin", LOCAL, (m, mode) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return fixture.session();
            }, LIMITS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.manager.disable("plugin");
            assertFalse(fixture.manager.awaitStopped("plugin", Duration.ZERO));
            assertThrows(PluginManagerException.class, () -> fixture.manager.uninstall("plugin"));
            release.countDown();
            var error = assertThrows(ExecutionException.class, () -> enabling.get(5, TimeUnit.SECONDS));
            assertEquals(PluginManagerException.Code.CONCURRENT_CHANGE,
                    ((PluginManagerException) error.getCause()).code());
            assertTrue(fixture.registry.list(DocumentParserProvider.class).isEmpty());
            assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
            assertEquals(1, fixture.closes.get());
        } finally { release.countDown(); fixture.stop(); shutdown(starter); }
    }

    @Test void waitingForStopDuringStartAlsoInvalidatesPendingPublication() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = new Fixture((r, c) -> EMPTY);
        var starter = Executors.newSingleThreadExecutor();
        try {
            var enabling = starter.submit(() -> fixture.manager.enableUnverified("plugin", LOCAL, (m, mode) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return fixture.session();
            }, LIMITS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // 无 disable：STARTING 时 Manager 仍显示 DISABLED，awaitStopped 也会请求停止。
            assertFalse(fixture.manager.awaitStopped("plugin", Duration.ZERO));
            release.countDown();
            var error = assertThrows(ExecutionException.class, () -> enabling.get(5, TimeUnit.SECONDS));
            assertEquals(PluginManagerException.Code.CONCURRENT_CHANGE,
                    ((PluginManagerException) error.getCause()).code());
            assertTrue(fixture.registry.list(DocumentParserProvider.class).isEmpty());
            assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
            assertEquals(1, fixture.closes.get());
        } finally { release.countDown(); fixture.stop(); shutdown(starter); }
    }

    @Test void metadataMismatchAfterStartClosesResourceWithoutPublishing() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        var closed = new AtomicInteger();
        assertThrows(PluginManagerException.class, () -> fixture.manager.enableUnverified("plugin", LOCAL,
                (m, mode) -> new PluginRuntimeFactory.Session() {
                    public Map<String, PluginProvider> providers() {
                        return Map.of("parser", new Parser("wrong", fixture.body));
                    }
                    public void close() { closed.incrementAndGet(); }
                }, LIMITS));
        assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        assertEquals(1, closed.get());
        assertTrue(fixture.registry.list(DocumentParserProvider.class).isEmpty());
    }

    @Test void failedStartHasSafeMessageAndCanBeRetried() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        var failure = assertThrows(PluginRuntimeException.class, () -> fixture.manager.enableUnverified("plugin", LOCAL,
                (m, mode) -> { throw new Exception("private-path"); }, LIMITS));
        assertEquals(PluginRuntimeException.Code.START_FAILED, failure.code());
        assertFalse(failure.getMessage().contains("private-path"));
        assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        fixture.enable(LOCAL);
        fixture.stop();
    }

    @Test void failedCleanupIsRetainedAndRetryDoesNotReleaseTwiceAfterSuccess() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        var attempts = new AtomicInteger();
        fixture.manager.enableUnverified("plugin", LOCAL, (m, mode) -> new PluginRuntimeFactory.Session() {
            public Map<String, PluginProvider> providers() { return Map.of("parser", new Parser("parser", fixture.body)); }
            public void close() throws Exception {
                if (attempts.incrementAndGet() == 1) { throw new Exception("private-close-detail"); }
            }
        }, LIMITS);
        fixture.manager.disable("plugin");
        var error = assertThrows(PluginRuntimeException.class,
                () -> fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        assertFalse(error.getMessage().contains("private-close-detail"));
        assertEquals(PluginRuntime.State.STOP_FAILED, fixture.manager.runtimeState("plugin"));
        assertThrows(PluginManagerException.class, () -> fixture.enable(LOCAL));
        assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        assertTrue(fixture.manager.awaitStopped("plugin", Duration.ZERO));
        assertEquals(2, attempts.get());
        fixture.manager.uninstall("plugin");
    }

    @Test void factoryReceivesDeploymentModeWithoutClaimingRealDockerExecution() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        var selected = new AtomicReference<PluginManifest.RuntimeMode>();
        fixture.manager.enableUnverified("plugin", DOCKER, (m, mode) -> {
            selected.set(mode);
            assertEquals("plugin", m.id());
            return fixture.session(); // Docker 适配器契约测试；没有启动真实容器。
        }, LIMITS);
        try { assertEquals(DOCKER, selected.get()); }
        finally { fixture.stop(); }
    }

    @Test void limitsAndDrainBudgetAreValidated() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PluginRuntime.Limits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PluginRuntime.Limits(1, 0));
        var fixture = new Fixture((r, c) -> EMPTY);
        fixture.enable(LOCAL);
        fixture.manager.disable("plugin");
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.manager.awaitStopped("plugin", Duration.ofSeconds(-1)));
        } finally { fixture.stop(); }
    }

    @Test void snapshotClosesHostStreamAndEnforcesRuntimeAndRequestLimits() throws Exception {
        var sourceCloses = new AtomicInteger();
        var calls = new AtomicInteger();
        var fixture = new Fixture((r, c) -> { calls.incrementAndGet(); return EMPTY; });
        fixture.manager.enableUnverified("plugin", LOCAL, (m, mode) -> fixture.session(), new PluginRuntime.Limits(1, 1, 4));
        DocumentContent source = () -> new ByteArrayInputStream(new byte[5]) {
            @Override public void close() { sourceCloses.incrementAndGet(); }
        };
        try {
            for (int requestLimit : List.of(3, 10)) {
                var request = new DocumentParseRequest("doc", "doc.pdf", "application/pdf", source, requestLimit, 1);
                assertEquals(ProviderException.Code.LIMIT_EXCEEDED,
                        assertThrows(ProviderException.class, () -> fixture.provider().parse(request, context())).code());
            }
            assertEquals(0, calls.get());
            assertEquals(2, sourceCloses.get());
            var exact = new DocumentParseRequest("doc", "doc.pdf", "application/pdf",
                    () -> new ByteArrayInputStream(new byte[4]), 4, 1);
            assertSame(EMPTY, fixture.provider().parse(exact, context()));
        } finally { fixture.stop(); }
    }

    @Test void snapshotReadFailureDoesNotConsumeAdmissionSlot() throws Exception {
        var fixture = new Fixture((r, c) -> EMPTY);
        fixture.enable(LOCAL);
        try {
            var broken = new DocumentParseRequest("doc", "doc.pdf", "application/pdf",
                    () -> { throw new java.io.IOException("private-storage-path"); }, 10, 1);
            for (int i = 0; i < 3; i++) {
                var error = assertThrows(ProviderException.class, () -> fixture.provider().parse(broken, context()));
                assertEquals(ProviderException.Code.INPUT_READ_FAILED, error.code());
                assertFalse(error.getMessage().contains("private-storage-path"));
            }
            assertSame(EMPTY, fixture.provider().parse(request(DocumentParseOptions.defaults()), context()));
        } finally { fixture.stop(); }
    }

    @Test void timedOutWorkerUsesOwnedSnapshotAfterHostSourceExpires() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hostAlive = new AtomicBoolean(true);
        var opens = new AtomicInteger();
        var bytesRead = new AtomicInteger(-1);
        var sourceClosed = new AtomicBoolean();
        var fixture = new Fixture((r, c) -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            try (var input = r.content().openStream()) { bytesRead.set(input.read()); }
            catch (java.io.IOException ex) { throw new AssertionError(ex); }
            return EMPTY;
        });
        DocumentContent content = () -> {
            assertTrue(hostAlive.get(), "host source was accessed after caller returned");
            opens.incrementAndGet();
            return new ByteArrayInputStream(new byte[]{42}) {
                public void close() { sourceClosed.set(true); }
            };
        };
        var caller = Executors.newSingleThreadExecutor();
        fixture.enable(LOCAL);
        try {
            var call = caller.submit(() -> assertThrows(ProviderException.class,
                    () -> fixture.provider().parse(new DocumentParseRequest("doc", "doc.pdf", "application/pdf", content, 10, 1),
                            new ProviderCallContext("owned", Instant.now().plusMillis(300)))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ProviderException.Code.DEADLINE_EXCEEDED, call.get(5, TimeUnit.SECONDS).code());
            hostAlive.set(false);
            assertTrue(sourceClosed.get());
            fixture.manager.disable("plugin");
            release.countDown();
            assertTrue(fixture.manager.awaitStopped("plugin", Duration.ofSeconds(5)));
            assertEquals(42, bytesRead.get());
            assertEquals(1, opens.get());
        } finally { release.countDown(); fixture.stop(); shutdown(caller); }
    }

    private static void waitUntilWaiting(AtomicReference<Thread> ref) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread thread = ref.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) { return; }
            Thread.sleep(1);
        }
        fail("caller did not wait for queued result");
    }
    private static void shutdown(ExecutorService service) throws Exception {
        service.shutdownNow();
        assertTrue(service.awaitTermination(5, TimeUnit.SECONDS));
    }
    private static DocumentParseRequest request(DocumentParseOptions options) {
        return new DocumentParseRequest("doc", "doc.pdf", "application/pdf",
                () -> new ByteArrayInputStream(new byte[0]), 1024, 10, options);
    }
    private static ProviderCallContext context() { return new ProviderCallContext("call", Instant.now().plusSeconds(20)); }
    @FunctionalInterface private interface Body {
        DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
                throws ProviderException, InterruptedException;
    }
    private record Parser(String id, Body body) implements DocumentParserProvider {
        public ProviderDescriptor descriptor() { return new ProviderDescriptor(id, "1"); }
        public Set<String> supportedMediaTypes() { return Set.of("application/pdf"); }
        public DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
                throws ProviderException, InterruptedException { return body.parse(request, context); }
    }
    private static final class Fixture {
        final ProviderRegistry registry = new ProviderRegistry();
        final PluginManager manager = new PluginManager(registry);
        final AtomicInteger closes = new AtomicInteger();
        final Body body;
        Fixture(Body body) {
            this.body = body;
            var declaration = new DocumentParserDeclaration(Set.of("application/pdf"), Set.of("zh-CN", "en"),
                    true, true, new DocumentParseOptions("zh-CN", false, true));
            manager.install(new PluginManifest(1, "plugin", "Plugin", "1", "", 1, Set.of(LOCAL, DOCKER),
                    List.of(new ProviderManifest("parser", "1", ProviderManifest.Capability.DOCUMENT_PARSER, declaration))));
        }
        PluginRuntimeFactory.Session session() {
            return new PluginRuntimeFactory.Session() {
                public Map<String, PluginProvider> providers() { return Map.of("parser", new Parser("parser", body)); }
                public void close() { closes.incrementAndGet(); }
            };
        }
        void enable(PluginManifest.RuntimeMode mode) { manager.enableUnverified("plugin", mode, (m, selected) -> session(), LIMITS); }
        DocumentParserProvider provider() { return registry.require(DocumentParserProvider.class, "parser"); }
        void stop() throws Exception {
            manager.disable("plugin");
            assertTrue(manager.awaitStopped("plugin", Duration.ofSeconds(5)));
        }
    }
}
