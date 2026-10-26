package com.ylcloud.gateway;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyAuthenticationFilterTest {
    private static final String KEY="ylk_"+"a".repeat(12)+"_"+"b".repeat(43);
    private static final String ID="12345678-1234-1234-1234-123456789abc";
    private static final String SECRET="gateway-api-key-test-secret-at-least-32";
    private final ApiKeyStore store=mock(ApiKeyStore.class);
    private final ApiKeyAuthenticationFilter filter=new ApiKeyAuthenticationFilter(store,new ApiKeyRoutePolicy(),new SessionSettings(true,false,SECRET,Duration.ofSeconds(2)));
    private MockServerWebExchange request(String method,String path,String... auth) {
        var builder=MockServerHttpRequest.method(HttpMethod.valueOf(method),path).header("X-Request-Id",ID).header(ApiKeyIdentityCodec.HEADER,"spoof");
        if(auth.length>0) builder.header("Authorization",auth);
        return MockServerWebExchange.from(builder);
    }
    @Test void signedIdentityReplacesSecretsAndCannotBeMovedToAnotherOperation() {
        when(store.authenticate(KEY)).thenReturn(Mono.just(new ApiKeyStore.Principal(3,7,Set.of("DRIVE_READ"),"")));
        var exchange=request("GET","/api/v1/files?parentId=2","Bearer "+KEY);
        filter.filter(exchange,forwarded->{
            assertNull(forwarded.getRequest().getHeaders().getFirst("Authorization"));
            var codec=new ApiKeyIdentityCodec(SECRET);
            var proof=forwarded.getRequest().getHeaders().getFirst(ApiKeyIdentityCodec.HEADER);
            assertEquals(3,codec.verify(proof,ID,"GET","/api/v1/files?parentId=2").keyId());
            assertThrows(IllegalArgumentException.class,()->codec.verify(proof,ID,"DELETE","/api/v1/files/2"));
            assertThrows(IllegalArgumentException.class,()->codec.verify(proof,ID,"GET","/api/v1/files?parentId=3"));
            assertThrows(IllegalArgumentException.class,()->codec.verify(proof,"ffffffff-1234-1234-1234-123456789abc","GET","/api/v1/files?parentId=2"));
            return Mono.empty();
        }).block();
    }
    @Test void missingDuplicateAndMalformedCredentialsNeverQueryDatabase() {
        for(String[] credentials:new String[][]{{},{"Bearer "+KEY,"Bearer "+KEY},{"Bearer bad"},{"Bearer "+KEY+", other"}}) {
            var exchange=request("GET","/api/open/files",credentials);
            filter.filter(exchange,ignored->Mono.error(new AssertionError())).block();
            assertEquals(HttpStatus.UNAUTHORIZED,exchange.getResponse().getStatusCode());
        }
        verifyNoInteractions(store);
    }
    @Test void unknownOperationsAreDeniedAndReadScopeCannotWrite() {
        when(store.authenticate(KEY)).thenReturn(Mono.just(new ApiKeyStore.Principal(3,7,Set.of("DRIVE_READ"),"")));
        for(String path:new String[]{"/api/v1/files/folders","/api/open/agent/sessions/1/queries"}) {
            var exchange=request("POST",path,"Bearer "+KEY);
            filter.filter(exchange,ignored->Mono.error(new AssertionError())).block();
            assertEquals(HttpStatus.FORBIDDEN,exchange.getResponse().getStatusCode());
        }
        var unknown=request("POST","/api/v1/admin/settings","Bearer "+KEY);
        filter.filter(unknown,ignored->Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.NOT_FOUND,unknown.getResponse().getStatusCode());
    }
    @Test void unavailableDatabaseFails503AndRevokedKeyFails401() {
        when(store.authenticate(KEY)).thenReturn(Mono.error(new IllegalStateException("db unavailable")),Mono.empty());
        for(var status:new HttpStatus[]{HttpStatus.SERVICE_UNAVAILABLE,HttpStatus.UNAUTHORIZED}) {
            var exchange=request("GET","/api/v1/usage","Bearer "+KEY);
            filter.filter(exchange,ignored->Mono.error(new AssertionError())).block();
            assertEquals(status,exchange.getResponse().getStatusCode());
            assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"meta\""));
        }
    }
    @Test void versionCutoffIsCheckedBeforeExecution() {
        when(store.authenticate(KEY)).thenReturn(Mono.just(new ApiKeyStore.Principal(3,7,Set.of("DRIVE_WRITE"),"2020-01-01T00:00:00Z")));
        var exchange=request("DELETE","/api/v1/files/4","Bearer "+KEY);
        filter.filter(exchange,ignored->Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.GONE,exchange.getResponse().getStatusCode());
    }
    @Test void routeAllowlistCoversAliasesAndRejectsNormalizationAmbiguity() {
        var policy=new ApiKeyRoutePolicy();
        assertNotNull(policy.resolve("HEAD","/api/open/usage/records"));
        assertNotNull(policy.resolve("POST","/api/v1/assistant/sessions/1/queries"));
        for(String path:new String[]{"/api/v1/files;bad","/api/v1/files/../files","/api/v1/files%2fupload","/api/v1//files"})
            assertNull(policy.resolve("POST",path));
    }
}
