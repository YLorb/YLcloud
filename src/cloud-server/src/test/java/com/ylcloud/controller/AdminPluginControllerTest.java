package com.ylcloud.controller;

import com.ylcloud.entity.User;
import com.ylcloud.handler.GlobalExceptionHandler;
import com.ylcloud.interceptor.*;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.service.*;
import com.ylcloud.pluginapi.*;
import com.ylcloud.plugin.manager.*;
import com.ylcloud.plugin.manifest.*;
import com.ylcloud.plugin.registry.*;
import com.ylcloud.plugin.runtime.*;
import com.ylcloud.plugin.spi.*;
import com.ylcloud.plugin.spi.document.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminPluginControllerTest {
    static final String ROOT="/api/admin/plugins";
    static final String JSON="""
            {"schemaVersion":1,"id":"example","name":"Example","version":"1","spiVersion":1,
             "runtimeModes":["LOCAL"],"providers":[{"id":"example.parser","version":"1","capability":"document-parser",
             "documentParsing":{"mediaTypes":["application/pdf"],"languages":[],"supportsTables":false,
             "supportsFormulas":false,"defaultOptions":{"language":null,"recognizeTables":false,"recognizeFormulas":false}}}]}
            """;
    final ProviderRegistry registry=new ProviderRegistry();
    final PluginManager manager=new PluginManager(registry);
    final AtomicInteger starts=new AtomicInteger(), closes=new AtomicInteger();
    final AtomicBoolean failHealth=new AtomicBoolean(), failClose=new AtomicBoolean();
    final AtomicReference<User> user=new AtomicReference<>();
    CountDownLatch startEntered, startRelease, parseEntered, parseRelease;
    PluginApiService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        User owner=new User(); owner.setId(7L); owner.setRole("ADMIN"); owner.setDeploymentOwner(true); user.set(owner);
        configure(true);
    }
    void configure(boolean backend) {
        var sessions=mock(BrowserSessionService.class);
        when(sessions.authenticate(any())).thenAnswer(invocation->user.get());
        var login=mock(LoginMapper.class);
        when(login.getById(7L)).thenAnswer(invocation->user.get());
        var permissions=new AdminPermissionService(login);
        service=new PluginApiService(manager,backend?List.of(binding()):List.of());
        mvc=MockMvcBuilders.standaloneSetup(new AdminPluginController(permissions,service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new BrowserCsrfInterceptor(),new SessionInterceptor(sessions)).build();
    }
    PluginBackendBinding binding() {
        var request=new DocumentParseRequest("fixture","fixture.pdf","application/pdf",()->new ByteArrayInputStream(new byte[]{1}),16,1);
        var plan=new PluginHealthPlan(Map.of("example.parser",List.of(new PluginHealthPlan.Probe(request,r->"ok".equals(r.fullText())))),Duration.ofSeconds(5));
        return new PluginBackendBinding("example","1",PluginManifest.RuntimeMode.LOCAL,(manifest,mode)-> {
            starts.incrementAndGet();
            if(startEntered!=null){startEntered.countDown();assertTrue(startRelease.await(5,TimeUnit.SECONDS));}
            return new PluginRuntimeFactory.Session(){
                public Map<String,PluginProvider> providers(){return Map.of("example.parser",new DocumentParserProvider(){
                    public ProviderDescriptor descriptor(){return new ProviderDescriptor("example.parser","1");}
                    public Set<String> supportedMediaTypes(){return Set.of("application/pdf");}
                    public DocumentParseResult parse(DocumentParseRequest request,ProviderCallContext context) throws InterruptedException {
                        if(parseEntered!=null){parseEntered.countDown();parseRelease.await();}
                        return new DocumentParseResult(failHealth.get()?"private-wrong-result":"ok",List.of(),List.of());
                    }
                });}
                public void close(){if(failClose.get()){throw new IllegalStateException("private-secret");}closes.incrementAndGet();}
            };
        },new PluginRuntime.Limits(1,2),plan);
    }
    static String q(String value){return Character.toString(34)+value+Character.toString(34);}
    ResultActions install(String json)throws Exception{return mvc.perform(post(ROOT).header("X-YLCloud-Request","1").contentType(MediaType.APPLICATION_JSON).content(json));}
    ResultActions enable()throws Exception{return mvc.perform(post(ROOT+"/example/enable").header("X-YLCloud-Request","1").param("mode","LOCAL"));}
    ResultActions disable()throws Exception{return mvc.perform(post(ROOT+"/example/disable").header("X-YLCloud-Request","1").param("waitMillis","100"));}
    @AfterEach void cleanup(){if(startRelease!=null)startRelease.countDown();if(parseRelease!=null)parseRelease.countDown();failClose.set(false);service.shutdown();}

    @Test void installEnableQueryCheckDisableUninstallLifecycle()throws Exception {
        install(JSON).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.installationKind").value("MANIFEST_REGISTRATION"))
                .andExpect(jsonPath("$.data.persistence").value("PROCESS_MEMORY"));
        enable().andExpect(status().isOk()).andExpect(jsonPath("$.data.managementState").value("ENABLED"))
                .andExpect(jsonPath("$.data.health.status").value("HEALTHY"));
        mvc.perform(get(ROOT)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].runtimeState").value("RUNNING"));
        mvc.perform(post(ROOT+"/example/health-check").header("X-YLCloud-Request","1")).andExpect(status().isOk());
        disable().andExpect(status().isOk()).andExpect(jsonPath("$.data.stopped").value(true));
        mvc.perform(delete(ROOT+"/example").header("X-YLCloud-Request","1")).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/example")).andExpect(status().isNotFound());
        assertEquals(1,starts.get());assertEquals(1,closes.get());
    }
    @Test void strictManifestRejectsDuplicatesUnknownKeysAndTooLargeBody()throws Exception {
        install(JSON.replace(q("schemaVersion")+":1",q("schemaVersion")+":1,"+q("schemaVersion")+":1")).andExpect(status().isBadRequest());
        install(JSON.replace(q("name")+":"+q("Example"),q("name")+":"+q("Example")+","+q("command")+":"+q("secret"))).andExpect(status().isBadRequest());
        install(" ".repeat(65537)).andExpect(status().isPayloadTooLarge());
        assertTrue(manager.list().isEmpty());assertEquals(0,starts.get());
    }
    @Test void noBackendAndVersionMismatchNeverClaimDeploymentSuccess()throws Exception {
        configure(false);install(JSON).andExpect(status().isCreated());
        enable().andExpect(status().isServiceUnavailable());assertEquals(0,starts.get());
        assertEquals(PluginManager.State.DISABLED,manager.get("example").state());
    }
    @Test void backendBindingMustMatchInstalledPackageVersion()throws Exception {
        install(JSON.replaceFirst(q("version")+":"+q("1"),q("version")+":"+q("2"))).andExpect(status().isCreated());
        enable().andExpect(status().isServiceUnavailable());assertEquals(0,starts.get());
    }
    @Test void duplicateOperationsConflictWithoutStartingMoreInstances()throws Exception {
        install(JSON).andExpect(status().isCreated());install(JSON).andExpect(status().isConflict());
        enable().andExpect(status().isOk());enable().andExpect(status().isConflict());
        mvc.perform(delete(ROOT+"/example").header("X-YLCloud-Request","1")).andExpect(status().isConflict());
        assertEquals(1,starts.get());disable().andExpect(status().isOk());disable().andExpect(status().isOk());assertEquals(1,closes.get());
    }
    @Test void sessionOwnerAndCsrfAreRequired()throws Exception {
        user.set(null);mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        var ordinary=new User();ordinary.setId(7L);ordinary.setRole("ADMIN");ordinary.setDeploymentOwner(false);user.set(ordinary);
        for(String path:List.of(ROOT,ROOT+"/example")){mvc.perform(get(path)).andExpect(status().isForbidden());}
        install(JSON).andExpect(status().isForbidden());enable().andExpect(status().isForbidden());disable().andExpect(status().isForbidden());
        mvc.perform(delete(ROOT+"/example").header("X-YLCloud-Request","1")).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/example/health-check").header("X-YLCloud-Request","1")).andExpect(status().isForbidden());
        ordinary.setDeploymentOwner(true);
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(JSON)).andExpect(status().isForbidden());
        mvc.perform(post(ROOT).header("X-YLCloud-Request","1").header("Sec-Fetch-Site","cross-site")
                .contentType(MediaType.APPLICATION_JSON).content(JSON)).andExpect(status().isForbidden());
        assertTrue(manager.list().isEmpty());
    }
    @Test void invalidModeAndWaitBudgetDoNotMutatePlugin()throws Exception {
        install(JSON).andExpect(status().isCreated());
        mvc.perform(post(ROOT+"/example/enable").header("X-YLCloud-Request","1").param("mode","SHELL")).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT+"/example/enable").header("X-YLCloud-Request","1").param("mode","DOCKER")).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT+"/example/disable").header("X-YLCloud-Request","1").param("waitMillis","5001")).andExpect(status().isBadRequest());
        assertEquals(0,manager.get("example").revision());
    }
    @Test void healthFailureIsSafeAndLeavesNoPublishedProvider()throws Exception {
        install(JSON).andExpect(status().isCreated());failHealth.set(true);
        var result=enable().andExpect(status().isUnprocessableEntity()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("private"));
        assertTrue(registry.list(DocumentParserProvider.class).isEmpty());
        assertTrue(manager.awaitStopped("example",Duration.ofSeconds(5)));
    }
    @Test void closeFailurePreservesRuntimeAndCanBeRetried()throws Exception {
        install(JSON);enable();failClose.set(true);
        var result=disable().andExpect(status().isServiceUnavailable()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("private-secret"));
        mvc.perform(get(ROOT+"/example")).andExpect(jsonPath("$.data.runtimeState").value("STOP_FAILED"));
        failClose.set(false);disable().andExpect(status().isOk());assertEquals(1,closes.get());
    }
    @Test void pendingDisableReturns202AndUninstallWaitsForActualStop()throws Exception {
        install(JSON);enable();parseEntered=new CountDownLatch(1);parseRelease=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var provider=registry.require(DocumentParserProvider.class,"example.parser");
            var future=executor.submit(()->provider.parse(new DocumentParseRequest("doc","doc.pdf","application/pdf",
                    ()->new ByteArrayInputStream(new byte[]{1}),16,1),new ProviderCallContext("business",Instant.now().plusSeconds(10))));
            assertTrue(parseEntered.await(5,TimeUnit.SECONDS));
            disable().andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(202))
                    .andExpect(jsonPath("$.data.stopped").value(false)).andExpect(jsonPath("$.data.plugin.runtimeState").value("STOPPING"));
            mvc.perform(delete(ROOT+"/example").header("X-YLCloud-Request","1")).andExpect(status().isConflict());
            parseRelease.countDown();future.get(5,TimeUnit.SECONDS);disable().andExpect(status().isOk());
        } finally {parseRelease.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void concurrentEnableConflictsAndStatusRemainsQueryable()throws Exception {
        install(JSON);startEntered=new CountDownLatch(1);startRelease=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->enable().andExpect(status().isOk()));
            assertTrue(startEntered.await(5,TimeUnit.SECONDS));
            mvc.perform(get(ROOT+"/example")).andExpect(status().isOk()).andExpect(jsonPath("$.data.runtimeState").value("STARTING"));
            enable().andExpect(status().isConflict());
            startRelease.countDown();first.get(5,TimeUnit.SECONDS);assertEquals(1,starts.get());
        } finally {startRelease.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void staleInstallAndRuntimeTokensCannotAffectReplacement()throws Exception {
        var reader=new PluginManifestReader();
        var old=reader.read(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)));
        manager.install(old);manager.uninstall("example");
        var replacement=reader.read(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)));manager.install(replacement);
        var binding=binding();
        assertThrows(PluginManagerException.class,()->manager.enableIfInstalled("example",binding.mode(),binding.factory(),binding.limits(),binding.healthPlan(),old));
        assertThrows(PluginManagerException.class,()->manager.awaitStoppedIfInstalled("example",Duration.ZERO,old,null));
        assertEquals(0,starts.get());
        manager.enable("example",binding.mode(),binding.factory(),binding.limits(),binding.healthPlan());
        var runtimeId=manager.runtimeHealth("example").runtimeId();manager.disable("example");manager.awaitStopped("example",Duration.ofSeconds(5));
        manager.enable("example",binding.mode(),binding.factory(),binding.limits(),binding.healthPlan());manager.disable("example");
        assertThrows(PluginManagerException.class,()->manager.awaitStoppedIfInstalled("example",Duration.ZERO,replacement,runtimeId));
    }
}
