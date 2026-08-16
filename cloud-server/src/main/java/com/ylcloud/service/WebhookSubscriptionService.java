package com.ylcloud.service;

import com.ylcloud.DTO.WebhookSubscriptionCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.WebhookSubscriptionCreatedVO;
import com.ylcloud.VO.WebhookSubscriptionVO;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.WebhookMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.webhook.WebhookSecretCipher;
import com.ylcloud.webhook.WebhookTargetPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WebhookSubscriptionService {
    public static final Set<String> EVENT_TYPES = Set.of(
            "FILE_CREATED","FILE_UPDATED","FILE_DELETED",
            "KNOWLEDGE_INDEXED","KNOWLEDGE_REMOVED","KNOWLEDGE_FAILED",
            "AGENT_TASK_COMPLETED","AGENT_TASK_FAILED","SPACE_MEMBER_CHANGED"
    );
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WebhookMapper mapper;
    private final UserApiKeyService apiKeys;
    private final SiteSettingService settings;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookSecretCipher secretCipher;

    @Transactional
    public WebhookSubscriptionCreatedVO create(Long userId,WebhookSubscriptionCreateDTO dto) {
        if(!settings.getBoolean("webhook.enabled",true)) throw new BaseException(403,"站点已停用 Webhook");
        ApiKeyPrincipal principal = apiKeys.requireOwnedActivePrincipal(userId,dto.getApiKeyId());
        Set<String> events = normalizeEvents(dto.getEventTypes());
        requireScopes(principal,events);
        String target = targetPolicy.requireAllowed(dto.getTargetUrl().trim()).toString();
        String secret = newSecret();
        LocalDateTime now = LocalDateTime.now();
        WebhookSubscription value = new WebhookSubscription();
        value.setUserId(userId);
        value.setApiKeyId(principal.keyId());
        value.setName(dto.getName().trim());
        value.setTargetUrl(target);
        value.setEventTypes(String.join(",",events));
        value.setIncludeContent(dto.isIncludeContent());
        value.setStatus("ACTIVE");
        value.setSecretCipher(secretCipher.encrypt(secret));
        value.setCreateTime(now);
        value.setUpdateTime(now);
        mapper.insertSubscription(value);
        return new WebhookSubscriptionCreatedVO(toVO(value),secret);
    }

    public List<WebhookSubscriptionVO> list(Long userId) {
        return mapper.listOwnedSubscriptions(userId).stream().map(this::toVO).toList();
    }

    @Transactional
    public void disable(Long userId,Long subscriptionId) {
        if(mapper.disableSubscription(subscriptionId,userId,LocalDateTime.now()) != 1) {
            throw new BaseException("Webhook 订阅不存在或已停用");
        }
    }

    @Transactional
    public WebhookSubscriptionCreatedVO rotateSecret(Long userId,Long subscriptionId) {
        WebhookSubscription current = mapper.getOwnedSubscription(subscriptionId,userId);
        if(current == null || !"ACTIVE".equals(current.getStatus())) throw new BaseException("Webhook 订阅不存在或已停用");
        String secret = newSecret();
        LocalDateTime now = LocalDateTime.now();
        long graceHours = settings.getLong("webhook.secretRotationGraceHours",24L);
        LocalDateTime until = now.plusHours(Math.max(0,graceHours));
        if(mapper.rotateSecret(subscriptionId,userId,secretCipher.encrypt(secret),until,now) != 1) {
            throw new BaseException("Webhook Secret 轮换失败");
        }
        return new WebhookSubscriptionCreatedVO(toVO(mapper.getOwnedSubscription(subscriptionId,userId)),secret);
    }

    public Set<String> eventCatalog() {
        return new java.util.TreeSet<>(EVENT_TYPES);
    }

    private Set<String> normalizeEvents(Set<String> values) {
        Set<String> normalized = new java.util.TreeSet<>();
        if(values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toUpperCase()).forEach(normalized::add);
        if(normalized.isEmpty() || !EVENT_TYPES.containsAll(normalized)) throw new BaseException("Webhook 事件类型不合法");
        return normalized;
    }

    private void requireScopes(ApiKeyPrincipal principal,Set<String> events) {
        for(String event : events) {
            if(event.startsWith("FILE_") && !principal.scopes().contains(UserApiKeyService.DRIVE_READ)) {
                throw new BaseException(403,"文件 Webhook 需要 DRIVE_READ Scope");
            }
            if((event.startsWith("KNOWLEDGE_") || event.startsWith("SPACE_"))
                    && !principal.scopes().contains(UserApiKeyService.KNOWLEDGE_RETRIEVE)
                    && !principal.scopes().contains(UserApiKeyService.KNOWLEDGE_AGENT)) {
                throw new BaseException(403,"知识或 Space Webhook 需要知识 Scope");
            }
            if(event.startsWith("AGENT_") && !principal.scopes().contains(UserApiKeyService.KNOWLEDGE_AGENT)) {
                throw new BaseException(403,"Agent Webhook 需要 KNOWLEDGE_AGENT Scope");
            }
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private WebhookSubscriptionVO toVO(WebhookSubscription value) {
        WebhookSubscriptionVO vo = new WebhookSubscriptionVO();
        vo.setId(value.getId());
        vo.setName(value.getName());
        vo.setTargetUrl(value.getTargetUrl());
        vo.setApiKeyId(value.getApiKeyId());
        vo.setEventTypes(value.getEventTypes() == null || value.getEventTypes().isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(value.getEventTypes().split(","))));
        vo.setIncludeContent(value.getIncludeContent());
        vo.setStatus(value.getStatus());
        vo.setPreviousSecretValidUntil(value.getPreviousSecretValidUntil());
        vo.setLastDeliveryAt(value.getLastDeliveryAt());
        vo.setCreateTime(value.getCreateTime());
        return vo;
    }
}
