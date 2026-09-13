package com.ylcloud.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.WebhookDelivery;
import com.ylcloud.entity.WebhookEvent;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.SiteSettingService;
import com.ylcloud.service.UserApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryWorker {
    private final WebhookMapper mapper;
    private final UserApiKeyService apiKeys;
    private final SiteSettingService settings;
    private final WebhookSecretCipher secretCipher;
    private final WebhookHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${ylcloud.webhook.poll-delay-ms:1000}",
            initialDelayString = "${ylcloud.webhook.initial-delay-ms:3000}")
    public void poll() {
        if(!settings.getBoolean("webhook.enabled",true)) return;
        LocalDateTime now = LocalDateTime.now();
        mapper.listClaimableDeliveries(now,50).forEach(this::deliver);
    }

    public void deliver(Long deliveryId) {
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        if(mapper.claimDelivery(deliveryId,token,now.plusMinutes(2),now) != 1) return;
        WebhookDelivery delivery = mapper.getClaimedDelivery(deliveryId,token);
        if(delivery == null) return;
        Integer responseStatus = null;
        try {
            WebhookSubscription subscription = mapper.getSubscription(delivery.getSubscriptionId());
            WebhookEvent event = mapper.getEvent(delivery.getEventId());
            if(subscription == null || event == null || !"ACTIVE".equals(subscription.getStatus())
                    || !event.getUserId().equals(subscription.getUserId())) {
                dead(delivery,token,responseStatus,"SUBSCRIPTION_INACTIVE");
                return;
            }
            ApiKeyPrincipal principal = apiKeys.requireOwnedActivePrincipal(subscription.getUserId(),subscription.getApiKeyId());
            String body = payload(event,subscription,principal);
            long timestamp = System.currentTimeMillis() / 1000;
            Map<String,String> headers = new LinkedHashMap<>();
            headers.put("X-YLCloud-Event-Id",event.getEventId());
            headers.put("X-YLCloud-Event-Type",event.getEventType());
            headers.put("X-YLCloud-Timestamp",String.valueOf(timestamp));
            headers.put("X-YLCloud-Signature",WebhookSigner.sign(secretCipher.decrypt(subscription.getSecretCipher()),timestamp,body));
            if(subscription.getPreviousSecretCipher() != null && subscription.getPreviousSecretValidUntil() != null
                    && subscription.getPreviousSecretValidUntil().isAfter(LocalDateTime.now())) {
                headers.put("X-YLCloud-Signature-Previous",WebhookSigner.sign(
                        secretCipher.decrypt(subscription.getPreviousSecretCipher()),timestamp,body));
            }
            long timeoutSeconds = Math.max(1,Math.min(60,settings.getLong("webhook.deliveryTimeoutSeconds",10L)));
            responseStatus = httpClient.post(subscription.getTargetUrl(),body,headers,Duration.ofSeconds(timeoutSeconds));
            if(responseStatus >= 200 && responseStatus < 300) {
                LocalDateTime completed = LocalDateTime.now();
                mapper.markDelivered(deliveryId,token,responseStatus,completed);
                mapper.touchSubscription(subscription.getId(),completed);
                return;
            }
            if(responseStatus != 408 && responseStatus != 429 && responseStatus < 500) {
                dead(delivery,token,responseStatus,"NON_RETRYABLE_HTTP_" + responseStatus);
                return;
            }
            retryOrDead(delivery,token,responseStatus,"RETRYABLE_HTTP_" + responseStatus);
        } catch(WebhookTargetPolicy.TargetResolutionException exception) {
            retryOrDead(delivery,token,responseStatus,"TARGET_DNS_UNAVAILABLE");
        } catch(BaseException exception) {
            dead(delivery,token,responseStatus,safe(exception.getMessage()));
        } catch(Exception exception) {
            retryOrDead(delivery,token,responseStatus,"NETWORK_OR_TIMEOUT");
        }
    }

    private String payload(WebhookEvent event,WebhookSubscription subscription,ApiKeyPrincipal principal) throws Exception {
        Map<String,Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId",event.getEventId());
        envelope.put("eventType",event.getEventType());
        envelope.put("resourceVersion",event.getResourceVersion());
        envelope.put("occurredAt",event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant().toString());
        envelope.put("resource",Map.of("type",event.getResourceType(),"id",event.getResourceId()));
        envelope.put("data",objectMapper.readValue(event.getMinimalPayloadJson(),new TypeReference<Map<String,Object>>(){}));
        if(Boolean.TRUE.equals(subscription.getIncludeContent()) && event.getContentPayloadJson() != null
                && contentAllowed(principal,event)) {
            envelope.put("content",objectMapper.readValue(event.getContentPayloadJson(),new TypeReference<Map<String,Object>>(){}));
        }
        return objectMapper.writeValueAsString(envelope);
    }

    private boolean contentAllowed(ApiKeyPrincipal principal,WebhookEvent event) {
        try {
            if(event.getEventType().startsWith("FILE_")) {
                if(event.getFileId() == null) return false;
                apiKeys.requireDriveResource(principal,event.getFileId(),false);
            } else if(event.getEventType().startsWith("AGENT_")) {
                if(event.getSpaceId() == null) return false;
                apiKeys.requireSpace(principal,event.getSpaceId(),true);
            } else if(event.getEventType().startsWith("KNOWLEDGE_") || event.getEventType().startsWith("SPACE_")) {
                if(event.getSpaceId() == null) return false;
                apiKeys.requireSpace(principal,event.getSpaceId(),false);
            }
            return true;
        } catch(Exception ignored) {
            return false;
        }
    }

    private void retryOrDead(WebhookDelivery delivery,String token,Integer status,String error) {
        int maximum = Math.max(1,settings.getLong("webhook.maxAttempts",8L).intValue());
        if(delivery.getAttemptCount() >= maximum) {
            dead(delivery,token,status,error);
            return;
        }
        long seconds = Math.min(3600,5L << Math.min(10,Math.max(0,delivery.getAttemptCount()-1)));
        mapper.releaseDelivery(delivery.getId(),token,LocalDateTime.now().plusSeconds(seconds),status,safe(error),LocalDateTime.now());
    }

    private void dead(WebhookDelivery delivery,String token,Integer status,String error) {
        mapper.markDead(delivery.getId(),token,status,safe(error),LocalDateTime.now());
        log.warn("Webhook delivery dead: deliveryId={}, eventId={}, reason={}",delivery.getId(),delivery.getEventId(),safe(error));
    }

    private String safe(String value) {
        if(value == null || value.isBlank()) return "WEBHOOK_DELIVERY_FAILED";
        String safe = value.replaceAll("(?i)(token|password|secret|api[-_ ]?key)(\\s*[:=]\\s*)\\S+","$1$2[REDACTED]");
        return safe.length() <= 1000 ? safe : safe.substring(0,1000);
    }
}
