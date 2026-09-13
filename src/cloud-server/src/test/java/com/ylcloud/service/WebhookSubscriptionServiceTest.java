package com.ylcloud.service;

import com.ylcloud.DTO.WebhookSubscriptionCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.webhook.WebhookSecretCipher;
import com.ylcloud.webhook.WebhookTargetPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookSubscriptionServiceTest {
    @Test
    void createsScopedSubscriptionWithoutPersistingPlaintextSecret() {
        WebhookMapper mapper = mock(WebhookMapper.class);
        UserApiKeyService keys = mock(UserApiKeyService.class);
        SiteSettingService settings = mock(SiteSettingService.class);
        WebhookTargetPolicy policy = mock(WebhookTargetPolicy.class);
        WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(mapper,keys,settings,policy,cipher);
        when(settings.getBoolean("webhook.enabled",true)).thenReturn(true);
        when(keys.requireOwnedActivePrincipal(7L,3L)).thenReturn(new ApiKeyPrincipal(3L,7L,"prefix","READ",1L,
                Set.of(UserApiKeyService.DRIVE_READ),Set.of()));
        when(policy.requireAllowed("https://example.com/hooks")).thenReturn(URI.create("https://example.com/hooks"));
        when(cipher.encrypt(org.mockito.ArgumentMatchers.anyString())).thenReturn("v1:ciphertext");
        WebhookSubscriptionCreateDTO dto = dto(Set.of("FILE_CREATED"));

        var result = service.create(7L,dto);

        ArgumentCaptor<WebhookSubscription> saved = ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(mapper).insertSubscription(saved.capture());
        assertTrue(result.secret().startsWith("whsec_"));
        assertFalse(saved.getValue().getSecretCipher().contains(result.secret()));
        assertTrue(result.subscription().getEventTypes().contains("FILE_CREATED"));
    }

    @Test
    void rejectsEventCatalogOutsideApiKeyScope() {
        WebhookMapper mapper = mock(WebhookMapper.class);
        UserApiKeyService keys = mock(UserApiKeyService.class);
        SiteSettingService settings = mock(SiteSettingService.class);
        WebhookSubscriptionService service = new WebhookSubscriptionService(mapper,keys,settings,
                mock(WebhookTargetPolicy.class),mock(WebhookSecretCipher.class));
        when(settings.getBoolean("webhook.enabled",true)).thenReturn(true);
        when(keys.requireOwnedActivePrincipal(7L,3L)).thenReturn(new ApiKeyPrincipal(3L,7L,"prefix","READ",1L,
                Set.of(UserApiKeyService.DRIVE_READ),Set.of()));

        assertThrows(BaseException.class,() -> service.create(7L,dto(Set.of("AGENT_TASK_COMPLETED"))));
        verify(mapper,never()).insertSubscription(org.mockito.ArgumentMatchers.any());
    }

    private WebhookSubscriptionCreateDTO dto(Set<String> events) {
        WebhookSubscriptionCreateDTO dto = new WebhookSubscriptionCreateDTO();
        dto.setName("build hook");
        dto.setTargetUrl("https://example.com/hooks");
        dto.setApiKeyId(3L);
        dto.setEventTypes(events);
        dto.setIncludeContent(true);
        return dto;
    }
}
