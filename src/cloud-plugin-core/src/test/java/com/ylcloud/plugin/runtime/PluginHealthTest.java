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
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static com.ylcloud.plugin.manifest.PluginManifest.RuntimeMode.LOCAL;

class PluginHealthTest {
    private static final DocumentParseResult OK = new DocumentParseResult("ok", List.of(), List.of());
    @FunctionalInterface interface Body {
        DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context) throws ProviderException, InterruptedException;
    }

    @Test void allProvidersAreVerifiedBeforeAnyPublication() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 2);
            var calls = new AtomicInteger();
            h.enable("one", (r,c) -> {
                assertTrue(h.registry.list(DocumentParserProvider.class).isEmpty());
                calls.incrementAndGet(); return OK;
            });
            assertEquals(2, calls.get());
            assertEquals(2, h.registry.list(DocumentParserProvider.class).size());
            assertEquals(PluginRuntime.Health.HEALTHY, h.manager.runtimeHealth("one").status());
            assertNotNull(h.manager.runtimeHealth("one").checkedAt());
        }
    }

    @Test void failedAssertionPublishesNothingAndReleasesSession() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 2);
            var error = assertThrows(PluginHealthException.class,
                    () -> h.enable("one", (r,c) -> new DocumentParseResult("wrong", List.of(), List.of())));
            assertFalse(error.getMessage().contains("wrong"));
            assertTrue(h.registry.list(DocumentParserProvider.class).isEmpty());
            assertEquals(PluginManager.State.DISABLED, h.manager.get("one").state());
            assertTrue(h.manager.awaitStopped("one", Duration.ofSeconds(5)));
            assertEquals(1, h.closes.get());
        }
    }

    @Test void missingProbePlanRejectedBeforeFactoryStarts() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 1);
            assertThrows(IllegalArgumentException.class, () -> h.manager.enable("one", LOCAL,
                    (m, mode) -> { fail("factory must not start"); return null; }, h.limits,
                    new PluginHealthPlan(Map.of(), Duration.ofSeconds(1))));
            assertNull(h.manager.runtimeState("one"));
        }
    }

    @Test void coverageRequiresMediaLanguageTablesAndFormulasAndCopiesInputs() {
        var declaration = new DocumentParserDeclaration(Set.of("application/pdf", "image/png"), Set.of("en", "zh-CN"),
                true, true, new DocumentParseOptions("en", false, false));
        var manifest = new PluginManifest(1, "one", "one", "1", "", 1, Set.of(LOCAL),
                List.of(new ProviderManifest("one.p0", "1", ProviderManifest.Capability.DOCUMENT_PARSER, declaration)));
        var list = new ArrayList<PluginHealthPlan.Probe>();
        list.add(new PluginHealthPlan.Probe(request("application/pdf", new DocumentParseOptions("en", true, false)), r -> true));
        var incomplete = new PluginHealthPlan(Map.of("one.p0", list), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> incomplete.validate(manifest));
        list.add(new PluginHealthPlan.Probe(request("image/png", new DocumentParseOptions("zh-CN", false, true)), r -> true));
        var complete = new PluginHealthPlan(Map.of("one.p0", list), Duration.ofSeconds(1));
        list.clear();
        complete.validate(manifest);
        assertThrows(UnsupportedOperationException.class, () -> complete.probes().clear());
        assertThrows(IllegalArgumentException.class, () -> new PluginHealthPlan(Map.of(), Duration.ZERO));
    }

    @Test void threeBackendFailuresIsolateWholePackageButNotOtherPlugin() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 2); h.install("two", 1);
            var fail = new AtomicBoolean();
            var calls = new AtomicInteger();
            h.enable("one", (r,c) -> {
                calls.incrementAndGet();
                if (fail.get()) { throw new ProviderException(ProviderException.Code.UNAVAILABLE, "backend unavailable"); }
                return OK;
            });
            h.enable("two", (r,c) -> OK);
            var retained = h.provider("one.p0");
            fail.set(true);
            for (int i=0; i<3; i++) { assertThrows(ProviderException.class, () -> call(retained)); }
            assertEquals(PluginRuntime.Health.ISOLATED, h.manager.runtimeHealth("one").status());
            int count = calls.get();
            assertThrows(ProviderException.class, () -> call(retained));
            assertThrows(ProviderException.class, () -> call(h.provider("one.p1")));
            assertEquals(count, calls.get());
            assertSame(OK, call(h.provider("two.p0")));
            assertThrows(PluginHealthException.class, () -> h.manager.checkHealth("one", h.plan("one")));
        }
    }

    @Test void documentErrorsAndUnsupportedOptionsDoNotCountAsBackendFailures() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 1);
            var error = new AtomicReference<ProviderException.Code>();
            h.enable("one", (r,c) -> {
                if (error.get()!=null) { throw new ProviderException(error.get(), "document rejected"); }
                return OK;
            });
            for (var code : List.of(ProviderException.Code.INVALID_DOCUMENT, ProviderException.Code.UNSUPPORTED_OPTION,
                    ProviderException.Code.LIMIT_EXCEEDED, ProviderException.Code.INPUT_READ_FAILED)) {
                error.set(code);
                for (int i=0; i<3; i++) { assertThrows(ProviderException.class, () -> call(h.provider("one.p0"))); }
            }
            assertEquals(0, h.manager.runtimeHealth("one").consecutiveFailures());
            assertEquals(PluginRuntime.Health.HEALTHY, h.manager.runtimeHealth("one").status());
        }
    }

    @Test void successResetsCounterAndRestartUsesNewRuntimeIdentity() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 1);
            var fail = new AtomicBoolean();
            Body body = (r,c) -> { if (fail.get()) { throw new IllegalStateException("private"); } return OK; };
            h.enable("one", body);
            var old = h.provider("one.p0");
            var id = h.manager.runtimeHealth("one").runtimeId();
            fail.set(true);
            for(int i=0;i<2;i++) { assertThrows(ProviderException.class, () -> call(old)); }
            fail.set(false); call(old);
            assertEquals(0, h.manager.runtimeHealth("one").consecutiveFailures());
            fail.set(true);
            for(int i=0;i<3;i++) { assertThrows(ProviderException.class, () -> call(old)); }
            h.manager.disable("one");
            assertTrue(h.manager.awaitStopped("one", Duration.ofSeconds(5)));
            fail.set(false); h.enable("one", body);
            assertNotEquals(id, h.manager.runtimeHealth("one").runtimeId());
            assertThrows(ProviderException.class, () -> call(old));
            assertSame(OK, call(h.provider("one.p0")));
        }
    }

    @Test void explicitRecheckFailureBlocksCallsAndSuccessfulRecheckRestoresThem() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 1); h.enable("one", (r,c) -> OK);
            var falsePlan = h.plan("one", r -> false, Duration.ofSeconds(1));
            assertThrows(PluginHealthException.class, () -> h.manager.checkHealth("one", falsePlan));
            assertEquals(PluginRuntime.Health.UNHEALTHY, h.manager.runtimeHealth("one").status());
            assertThrows(ProviderException.class, () -> call(h.provider("one.p0")));
            h.manager.checkHealth("one", h.plan("one"));
            assertSame(OK, call(h.provider("one.p0")));
        }
    }

    @Test void assertionErrorProducesSafeUnhealthyResultInsteadOfStuckChecking() throws Exception {
        try (var h = new Harness()) {
            h.install("one", 1); h.enable("one", (r,c) -> OK);
            var plan = h.plan("one", r -> { throw new AssertionError("secret-fixture"); }, Duration.ofSeconds(1));
            var error = assertThrows(PluginHealthException.class, () -> h.manager.checkHealth("one", plan));
            assertFalse(error.getMessage().contains("secret-fixture"));
            assertEquals(PluginRuntime.Health.UNHEALTHY, h.manager.runtimeHealth("one").status());
        }
    }

    @Test void disableDuringProbeCannotPublishOrRestoreHealth() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var h = new Harness()) {
            h.install("one", 1);
            var result = executor.submit(() -> h.enable("one", (r,c) -> { entered.countDown(); release.await(); return OK; }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(h.registry.list(DocumentParserProvider.class).isEmpty());
            h.manager.disable("one"); release.countDown();
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(h.registry.list(DocumentParserProvider.class).isEmpty());
            assertEquals(PluginRuntime.Health.INACTIVE, h.manager.runtimeHealth("one").status());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void probeDeadlineIsSharedAcrossCasesAndRetainsUncooperativeWorker() throws Exception {
        var release = new CountDownLatch(1);
        try (var h = new Harness()) {
            h.install("one", 1);
            try {
                assertThrows(PluginHealthException.class, () -> h.manager.enable("one", LOCAL, h.factory("one", (r,c) -> {
                    while(release.getCount()!=0) { try { release.await(); } catch (InterruptedException ignored) { } }
                    return OK;
                }), h.limits, h.plan("one", r -> true, Duration.ofMillis(100))));
                assertTrue(h.registry.list(DocumentParserProvider.class).isEmpty());
                assertEquals(0, h.closes.get());
                assertFalse(h.manager.awaitStopped("one", Duration.ZERO));
            } finally { release.countDown(); }
        }
    }

    @Test void overlappingHealthChecksAreRejectedWithoutChangingFirstCheck() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var h = new Harness()) {
            h.install("one",1); h.enable("one", (r,c)->OK);
            try {
                var plan = h.plan("one", r -> { entered.countDown(); await(release); return true; }, Duration.ofSeconds(5));
                var result = executor.submit(() -> h.manager.checkHealth("one", plan));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertThrows(PluginHealthException.class, () -> h.manager.checkHealth("one", h.plan("one")));
                assertEquals(PluginRuntime.Health.CHECKING,h.manager.runtimeHealth("one").status());
                assertThrows(ProviderException.class, () -> call(h.provider("one.p0")));
                release.countDown();
                assertEquals(PluginRuntime.Health.HEALTHY,result.get(5,TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @Test void lateSuccessCannotUndoIsolation() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var phase = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (var h = new Harness()) {
            h.install("one", 1);
            h.enable("one", (r,c) -> {
                if (phase.get() == 0) { return OK; }
                if (c.invocationId().equals("slow")) { entered.countDown(); release.await(); return OK; }
                throw new ProviderException(ProviderException.Code.INTERNAL_ERROR,"engine failure");
            });
            phase.set(1);
            try {
                var slow = executor.submit(() -> h.provider("one.p0").parse(request("application/pdf",DocumentParseOptions.defaults()),
                        new ProviderCallContext("slow",Instant.now().plusSeconds(5))));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                for(int i=0;i<3;i++) { assertThrows(ProviderException.class, () -> call(h.provider("one.p0"))); }
                assertEquals(PluginRuntime.Health.ISOLATED,h.manager.runtimeHealth("one").status());
                release.countDown(); slow.get(5,TimeUnit.SECONDS);
                assertEquals(PluginRuntime.Health.ISOLATED,h.manager.runtimeHealth("one").status());
                assertEquals(3,h.manager.runtimeHealth("one").consecutiveFailures());
            } finally { release.countDown(); }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @Test void queueTimeoutDoesNotCountButRunningTimeoutDoes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var block = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor();
        try(var h = new Harness()) {
            h.install("one",1);
            h.manager.enable("one",LOCAL,h.factory("one",(r,c)-> {
                if(block.get()) { entered.countDown(); release.await(); }
                return OK;
            }),new PluginRuntime.Limits(1,1),h.plan("one"));
            block.set(true);
            try {
                var first=executor.submit(()->call(h.provider("one.p0")));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                for(int i=0;i<3;i++) {
                    var error=assertThrows(ProviderException.class,()->h.provider("one.p0").parse(
                            request("application/pdf",DocumentParseOptions.defaults()),
                            new ProviderCallContext("queued",Instant.now().plusMillis(50))));
                    assertEquals(ProviderException.Code.DEADLINE_EXCEEDED,error.code());
                }
                assertEquals(0,h.manager.runtimeHealth("one").consecutiveFailures());
                release.countDown();first.get(5,TimeUnit.SECONDS);
            } finally {release.countDown();}
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        // 已进入引擎的超时则算后端故障；每次任务响应中断，下一次可以进入工作线程。
        try(var h=new Harness()) {
            h.install("one",1);
            var blockNow=new AtomicBoolean();
            h.enable("one",(r,c)-> {if(blockNow.get()){new CountDownLatch(1).await();}return OK;});
            blockNow.set(true);
            for(int i=0;i<3;i++) {
                assertEquals(ProviderException.Code.DEADLINE_EXCEEDED,assertThrows(ProviderException.class,()->h.provider("one.p0").parse(
                        request("application/pdf",DocumentParseOptions.defaults()),
                        new ProviderCallContext("running",Instant.now().plusMillis(100)))).code());
            }
            assertEquals(PluginRuntime.Health.ISOLATED,h.manager.runtimeHealth("one").status());
        }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
    static DocumentParseRequest request(String mime, DocumentParseOptions options) {
        return new DocumentParseRequest("health", "fixture", mime, () -> new ByteArrayInputStream(new byte[]{1}), 16, 1, options);
    }
    static DocumentParseResult call(DocumentParserProvider provider) throws Exception {
        return provider.parse(request("application/pdf", DocumentParseOptions.defaults()),
                new ProviderCallContext("business", Instant.now().plusSeconds(5)));
    }
    static class Harness implements AutoCloseable {
        final ProviderRegistry registry = new ProviderRegistry();
        final PluginManager manager = new PluginManager(registry);
        final Map<String,PluginManifest> manifests = new HashMap<>();
        final AtomicInteger closes = new AtomicInteger();
        final PluginRuntime.Limits limits = new PluginRuntime.Limits(2,4);
        void install(String id,int count) {
            var declaration = new DocumentParserDeclaration(Set.of("application/pdf"),Set.of(),false,false,
                    new DocumentParseOptions(null,false,false));
            var providers = new ArrayList<ProviderManifest>();
            for(int i=0;i<count;i++) { providers.add(new ProviderManifest(id+".p"+i,"1",ProviderManifest.Capability.DOCUMENT_PARSER,declaration)); }
            var manifest = new PluginManifest(1,id,id,"1","",1,Set.of(LOCAL),providers);
            manifests.put(id,manifest); manager.install(manifest);
        }
        PluginHealthPlan plan(String id) { return plan(id,r -> "ok".equals(r.fullText()),Duration.ofSeconds(5)); }
        PluginHealthPlan plan(String id, Predicate<DocumentParseResult> accepts,Duration duration) {
            Map<String,List<PluginHealthPlan.Probe>> probes = new HashMap<>();
            manifests.get(id).providers().forEach(p -> probes.put(p.id(),List.of(new PluginHealthPlan.Probe(
                    request("application/pdf",DocumentParseOptions.defaults()),accepts))));
            return new PluginHealthPlan(probes,duration);
        }
        PluginRuntimeFactory factory(String id,Body body) {
            return (m,mode)-> new PluginRuntimeFactory.Session() {
                public Map<String,PluginProvider> providers() {
                    Map<String,PluginProvider> providers = new HashMap<>();
                    m.providers().forEach(p -> providers.put(p.id(),new DocumentParserProvider() {
                        public ProviderDescriptor descriptor(){return new ProviderDescriptor(p.id(),"1");}
                        public Set<String> supportedMediaTypes(){return Set.of("application/pdf");}
                        public DocumentParseResult parse(DocumentParseRequest r,ProviderCallContext c) throws ProviderException,InterruptedException{return body.parse(r,c);}
                    }));
                    return providers;
                }
                public void close(){closes.incrementAndGet();}
            };
        }
        PluginManager.Snapshot enable(String id,Body body){return manager.enable(id,LOCAL,factory(id,body),limits,plan(id));}
        DocumentParserProvider provider(String id){return registry.require(DocumentParserProvider.class,id);}
        public void close() throws Exception {
            for(var id:manifests.keySet()){manager.disable(id);assertTrue(manager.awaitStopped(id,Duration.ofSeconds(5)));}
        }
    }
}
