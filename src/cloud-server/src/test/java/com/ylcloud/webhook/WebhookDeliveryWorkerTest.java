package com.ylcloud.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.WebhookDelivery;
import com.ylcloud.entity.WebhookEvent;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.SiteSettingService;
import com.ylcloud.service.UserApiKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryWorkerTest {
    @Test
    void deliversStableEventIdWithCurrentAndPreviousRotationSignatures() throws Exception {
        Fixture fixture = fixture(1);
        fixture.subscription.setPreviousSecretCipher("old-cipher");
        fixture.subscription.setPreviousSecretValidUntil(LocalDateTime.now().plusHours(1));
        when(fixture.cipher.decrypt("current-cipher")).thenReturn("whsec_current");
        when(fixture.cipher.decrypt("old-cipher")).thenReturn("whsec_previous");
        when(fixture.client.post(anyString(),anyString(),anyMap(),any())).thenReturn(204);

        fixture.worker.deliver(9L);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(fixture.client).post(eq("https://example.com/hook"),body.capture(),headers.capture(),any(Duration.class));
        assertTrue(body.getValue().contains("\"eventId\":\"event-1\""));
        assertTrue(body.getValue().contains("private.txt"));
        assertTrue(headers.getValue().containsKey("X-YLCloud-Signature"));
        assertTrue(headers.getValue().containsKey("X-YLCloud-Signature-Previous"));
        verify(fixture.mapper).markDelivered(eq(9L),anyString(),eq(204),any());
    }

    @Test
    void stripsBusinessContentWhenLiveScopeNoLongerAllowsResource() throws Exception {
        Fixture fixture = fixture(1);
        when(fixture.cipher.decrypt("current-cipher")).thenReturn("whsec_current");
        doThrow(new BaseException(403,"out of scope")).when(fixture.apiKeys)
                .requireDriveResource(any(),eq(11L),eq(false));
        when(fixture.client.post(anyString(),anyString(),anyMap(),any())).thenReturn(200);

        fixture.worker.deliver(9L);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(fixture.client).post(anyString(),body.capture(),anyMap(),any());
        assertFalse(body.getValue().contains("private.txt"));
        assertFalse(body.getValue().contains("\"content\""));
    }

    @Test
    void timeoutAndTransientDnsFailureReleaseDeliveryWithBackoffForAtLeastOnceRetry() throws Exception {
        Fixture fixture = fixture(1);
        when(fixture.cipher.decrypt("current-cipher")).thenReturn("whsec_current");
        when(fixture.client.post(anyString(),anyString(),anyMap(),any())).thenThrow(new IOException("timeout"));

        fixture.worker.deliver(9L);

        verify(fixture.mapper).releaseDelivery(eq(9L),anyString(),any(),eq(null),eq("NETWORK_OR_TIMEOUT"),any());
        verify(fixture.mapper,never()).markDelivered(anyLong(),anyString(),anyInt(),any());

        Fixture dnsFixture = fixture(1);
        when(dnsFixture.cipher.decrypt("current-cipher")).thenReturn("whsec_current");
        when(dnsFixture.client.post(anyString(),anyString(),anyMap(),any()))
                .thenThrow(new WebhookTargetPolicy.TargetResolutionException(new IOException("temporary DNS failure")));

        dnsFixture.worker.deliver(9L);

        verify(dnsFixture.mapper).releaseDelivery(eq(9L),anyString(),any(),eq(null),eq("TARGET_DNS_UNAVAILABLE"),any());
    }

    @Test
    void exhaustedRetryBudgetMovesDeliveryToDeadLetter() throws Exception {
        Fixture fixture = fixture(3);
        when(fixture.cipher.decrypt("current-cipher")).thenReturn("whsec_current");
        when(fixture.client.post(anyString(),anyString(),anyMap(),any())).thenThrow(new IOException("timeout"));

        fixture.worker.deliver(9L);

        verify(fixture.mapper).markDead(eq(9L),anyString(),eq(null),eq("NETWORK_OR_TIMEOUT"),any());
        verify(fixture.mapper,never()).releaseDelivery(anyLong(),anyString(),any(),any(),anyString(),any());
    }

    @Test
    void revokedApiKeyStopsPendingDeliveryImmediately() throws Exception {
        Fixture fixture = fixture(1);
        when(fixture.apiKeys.requireOwnedActivePrincipal(7L,5L))
                .thenThrow(new BaseException(403,"API Key revoked"));

        fixture.worker.deliver(9L);

        verify(fixture.mapper).markDead(eq(9L),anyString(),eq(null),eq("API Key revoked"),any());
        verify(fixture.client,never()).post(anyString(),anyString(),anyMap(),any());
    }

    private Fixture fixture(int attempts) {
        WebhookMapper mapper = mock(WebhookMapper.class);
        UserApiKeyService apiKeys = mock(UserApiKeyService.class);
        SiteSettingService settings = mock(SiteSettingService.class);
        WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
        WebhookHttpClient client = mock(WebhookHttpClient.class);
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(9L); delivery.setEventId("event-1"); delivery.setSubscriptionId(3L); delivery.setAttemptCount(attempts);
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setId(3L); subscription.setUserId(7L); subscription.setApiKeyId(5L); subscription.setStatus("ACTIVE");
        subscription.setTargetUrl("https://example.com/hook"); subscription.setIncludeContent(true);
        subscription.setSecretCipher("current-cipher");
        WebhookEvent event = new WebhookEvent();
        event.setEventId("event-1"); event.setUserId(7L); event.setEventType("FILE_CREATED");
        event.setResourceType("FILE"); event.setResourceId("11"); event.setResourceVersion(2L); event.setFileId(11L);
        event.setMinimalPayloadJson("{\"fileId\":11}"); event.setContentPayloadJson("{\"name\":\"private.txt\"}");
        event.setOccurredAt(LocalDateTime.now());
        when(mapper.claimDelivery(eq(9L),anyString(),any(),any())).thenReturn(1);
        when(mapper.getClaimedDelivery(eq(9L),anyString())).thenReturn(delivery);
        when(mapper.getSubscription(3L)).thenReturn(subscription);
        when(mapper.getEvent("event-1")).thenReturn(event);
        when(apiKeys.requireOwnedActivePrincipal(7L,5L)).thenReturn(new ApiKeyPrincipal(5L,7L,"prefix","READ",1L,
                Set.of(UserApiKeyService.DRIVE_READ),Set.of()));
        when(settings.getLong("webhook.deliveryTimeoutSeconds",10L)).thenReturn(2L);
        when(settings.getLong("webhook.maxAttempts",8L)).thenReturn(3L);
        WebhookDeliveryWorker worker = new WebhookDeliveryWorker(mapper,apiKeys,settings,cipher,client,new ObjectMapper());
        return new Fixture(mapper,apiKeys,cipher,client,worker,subscription);
    }

    private record Fixture(WebhookMapper mapper,UserApiKeyService apiKeys,WebhookSecretCipher cipher,
                           WebhookHttpClient client,WebhookDeliveryWorker worker,WebhookSubscription subscription) { }
}
