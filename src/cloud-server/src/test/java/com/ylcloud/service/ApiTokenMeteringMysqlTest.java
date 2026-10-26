package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.QuotaAccount;
import com.ylcloud.entity.QuotaPolicy;
import com.ylcloud.mapper.QuotaMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.metering.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs on an explicitly selected disposable database already migrated through V65. */
@EnabledIfEnvironmentVariable(named="YLCLOUD_TOKEN_MYSQL_TEST",matches="true")
class ApiTokenMeteringMysqlTest {
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private ApiTokenMeteringService service;
    private UserApiKeyService keys;
    private QuotaPolicy policy;
    private long account,user;
    private final LocalDate period=LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1);
    @BeforeEach void setup() {
        String url=System.getenv("YLCLOUD_TOKEN_TEST_DB_URL");
        assertNotNull(url,"explicit test DB required");
        assertTrue(url.contains("gateway_test") || url.contains("token_test"),"disposable DB only");
        var ds=new DriverManagerDataSource(url,System.getenv("YLCLOUD_TOKEN_TEST_DB_USER"),System.getenv("YLCLOUD_TOKEN_TEST_DB_PASSWORD"));
        manager=new DataSourceTransactionManager(ds); db=new JdbcTemplate(ds);
        user=-ThreadLocalRandom.current().nextLong(10000,Long.MAX_VALUE/4);
        db.update("insert into quota_account(account_type,reference_id,createtime,updatetime) values('USER',?,now(),now())",user);
        account=db.queryForObject("select account_id from quota_account where account_type='USER' and reference_id=?",Long.class,user);
        db.update("insert into quota_usage_period(account_id,period_start,updatetime) values(?,?,now())",account,period);
        var mapper=mock(QuotaMapper.class); var qa=new QuotaAccount(); qa.setId(account);
        when(mapper.getAccount("USER",user)).thenReturn(qa);
        var policies=mock(QuotaService.class); policy=new QuotaPolicy(); policy.setMonthlyModelTokens(1000L);
        when(policies.policyForUser(user)).thenReturn(policy);
        keys=mock(UserApiKeyService.class);
        when(keys.requireOwnedActivePrincipal(eq(user),anyLong())).thenAnswer(inv->new ApiKeyPrincipal(inv.getArgument(1),user,"test","NONE",null,Set.of("KNOWLEDGE_RETRIEVE"),Set.of(5L)));
        service=new ApiTokenMeteringService(db,mapper,policies,keys,manager);
    }
    @AfterEach void cleanup() {
        ApiUsageContext.clear();
        if(db!=null && account!=0) {
            db.update("delete from api_key_token_usage where account_id=?",account);
            db.update("delete from quota_usage_period where account_id=?",account);
            db.update("delete from quota_account where account_id=?",account);
        }
    }
    private ApiUsageContext scope(long key,Long message) {
        var scope=ApiUsageContext.open(user,key,UUID.randomUUID().toString(),message);
        scope.authorize(List.of(5L),false); return scope;
    }
    private long counter(String name) { return db.queryForObject("select "+name+" from quota_usage_period where account_id=? and period_start=?",Long.class,account,period); }
    @Test void parallelKeysShareOneAtomicQuota() throws Exception {
        var executor=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<16;i++) { long key=i+1; attempts.add(()->{try(var ignored=scope(key,null)) { service.reserve("chat","model",100); return true; } catch(BaseException error) { assertEquals(429,error.getStatusCode()); return false; }}); }
            int accepted=0; for(var result:executor.invokeAll(attempts)) if(result.get(20,TimeUnit.SECONDS)) accepted++;
            assertEquals(10,accepted); assertEquals(1000,counter("model_tokens_reserved")); assertEquals(0,counter("model_tokens"));
        } finally { executor.shutdownNow(); }
    }
    @Test void settlementIsIdempotentAndChargesActualNotReservation() {
        try(var ignored=scope(1,null)) {
            String call=service.reserve("generate","rewrite",900);
            service.settle(call,13,7,20); service.settle(call,13,7,20);
            assertEquals(20,counter("model_tokens")); assertEquals(0,counter("model_tokens_reserved"));
            assertEquals(1,service.records(user,1,1,20).size()); assertTrue(service.records(user,2,1,20).isEmpty());
        }
    }
    @Test void concurrentWorkersCannotAdmitTwoUnresolvedCallsForSameTask() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Boolean> attempt=()->{ start.await(); try(var ignored=scope(1,99L)) { service.reserve("chat","model",100); return true; } catch(BaseException error) { assertEquals(503,error.getStatusCode()); return false; }};
            var first=executor.submit(attempt); var second=executor.submit(attempt); start.countDown();
            assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertEquals(100,counter("model_tokens_reserved")); assertEquals(1,service.records(user,1,1,20).size());
        } finally { executor.shutdownNow(); }
    }
    @Test void businessRollbackDoesNotEraseProviderCost() {
        try(var ignored=scope(1,null)) {
            new TransactionTemplate(manager).executeWithoutResult(status->{
                String call=service.reserve("chat","model",900); service.settle(call,20,10,30); status.setRollbackOnly();
            });
            assertEquals(30,counter("model_tokens"));
        }
    }
    @Test void missingUsageHoldsQuotaPoisonsContextAndRequiresAuditedResolution() {
        String call;
        try(var scope=scope(1,99L)) {
            call=service.reserve("chat","model",900);
            assertThrows(BaseException.class,()->service.settle(call,null,null,null));
            assertThrows(BaseException.class,scope::check);
            assertThrows(BaseException.class,()->service.reserve("chat","model",1));
        }
        assertEquals(900,counter("model_tokens_reserved")); assertEquals(0,counter("model_tokens"));
        try(var ignored=scope(1,99L)) { assertEquals(503,assertThrows(BaseException.class,()->service.reserve("chat","model",1)).getStatusCode()); }
        service.resolve(call,12,8,"synthetic provider audit record",7);
        service.resolve(call,12,8,"same resolution retry",7);
        assertEquals(20,counter("model_tokens")); assertEquals(0,counter("model_tokens_reserved"));
        assertEquals(7,db.queryForObject("select resolved_by from api_key_token_usage where call_id=?",Long.class,call));
        assertThrows(BaseException.class,()->service.resolve(call,13,8,"conflicting report",7));
    }
    @Test void settlementUsesOriginalMonthAndRecordsProviderOverrun() {
        try(var ignored=scope(1,null)) {
            String call=service.reserve("chat","model",900);
            LocalDate previous=period.minusMonths(1);
            db.update("update quota_usage_period set period_start=? where account_id=?",previous,account);
            db.update("update api_key_token_usage set period_start=? where call_id=?",previous,call);
            service.settle(call,900,300,1200);
            assertEquals(1200,db.queryForObject("select model_tokens from quota_usage_period where account_id=? and period_start=?",Long.class,account,previous));
            assertEquals(0,db.queryForObject("select model_tokens_reserved from quota_usage_period where account_id=?",Long.class,account));
        }
    }
    @Test void revokedKeyCannotReserveAndNoLedgerIsWritten() {
        doThrow(new BaseException(403,"revoked")).when(keys).requireOwnedActivePrincipal(user,1L);
        try(var ignored=scope(1,null)) { assertEquals(403,assertThrows(BaseException.class,()->service.reserve("chat","model",100)).getStatusCode()); }
        assertEquals(0,counter("model_tokens_reserved")); assertTrue(service.records(user,1,1,20).isEmpty());
    }
    @Test void interruptedReservationIsMarkedUnresolvedWithoutRefund() {
        try(var ignored=scope(1,null)) {
            String call=service.reserve("chat","model",900);
            db.update("update api_key_token_usage set created_at=UTC_TIMESTAMP()-interval 2 day where call_id=?",call);
            service.markAbandoned();
            assertEquals("UNRESOLVED",db.queryForObject("select usage_status from api_key_token_usage where call_id=?",String.class,call));
            assertEquals(900,counter("model_tokens_reserved"));
        }
    }
    @Test void realHttpModelUsageSettlesAndMissingUsageBlocksFurtherCalls() throws Exception {
        policy.setMonthlyModelTokens(30000L);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat",exchange->{
            assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
            String body=calls.incrementAndGet()==1 ? "{\"model\":\"actual-model\",\"answer\":\"synthetic\",\"promptTokens\":21,\"completionTokens\":9,\"totalTokens\":30}" : "{\"model\":\"actual-model\",\"answer\":\"usage missing\"}";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var settings=mock(SiteSettingService.class);
            when(settings.getString(anyString(),anyString())).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            var jwt=mock(com.ylcloud.workflow.security.ServiceJwtIssuer.class);
            when(jwt.issue(any(),anySet(),any(),anyLong())).thenReturn("synthetic-service-token");
            var client=new com.ylcloud.service.rag.HttpRagModelClient(new com.ylcloud.config.RagProperties(),settings,jwt);
            client.setMetering(service);
            var request=new com.ylcloud.service.rag.RagChatRequest(); request.setQuestion("synthetic"); request.setContexts(List.of("fixture")); request.setMaxTokens(100);
            try(var ignored=scope(1,null)) { assertEquals("synthetic",client.chat(request).getAnswer()); }
            assertEquals(30,counter("model_tokens")); assertEquals(0,counter("model_tokens_reserved"));
            assertEquals("actual-model",service.records(user,1,1,20).get(0).get("model"));
            try(var scope=scope(1,null)) {
                assertThrows(BaseException.class,()->client.chat(request));
                assertThrows(BaseException.class,scope::check);
                assertThrows(BaseException.class,()->client.chat(request));
            }
            assertEquals(2,calls.get()); assertEquals(30,counter("model_tokens")); assertTrue(counter("model_tokens_reserved")>0);
        } finally { server.stop(0); }
    }
}
