package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.WebhookEvent;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.WebhookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookEventService {
    private final WebhookMapper mapper;
    private final SpaceMemberMapper spaceMembers;
    private final ObjectMapper objectMapper;

    @Transactional
    public String publish(Long userId,String eventType,String resourceType,String resourceId,long resourceVersion,
                          Long fileId,Long spaceId,Map<String,Object> minimal,Map<String,Object> content) {
        if(userId == null || !WebhookSubscriptionService.EVENT_TYPES.contains(eventType)) return null;
        LocalDateTime now = LocalDateTime.now();
        String eventKey = eventType + ":" + resourceType + ":" + resourceId + ":" + resourceVersion + ":" + userId;
        try {
            WebhookEvent value = new WebhookEvent();
            value.setEventId(UUID.randomUUID().toString());
            value.setEventKey(eventKey);
            value.setUserId(userId);
            value.setEventType(eventType);
            value.setResourceType(resourceType);
            value.setResourceId(resourceId);
            value.setResourceVersion(Math.max(1,resourceVersion));
            value.setFileId(fileId);
            value.setSpaceId(spaceId);
            value.setMinimalPayloadJson(objectMapper.writeValueAsString(minimal == null ? Map.of() : minimal));
            value.setContentPayloadJson(content == null || content.isEmpty() ? null : objectMapper.writeValueAsString(content));
            value.setOccurredAt(now);
            value.setCreateTime(now);
            if(mapper.insertEvent(value) == 0) value = mapper.getEventByKey(eventKey);
            for(WebhookSubscription subscription : mapper.listActiveSubscriptions(userId)) {
                if(eventTypes(subscription).contains(eventType)) mapper.insertDelivery(value.getEventId(),subscription.getId(),now);
            }
            return value.getEventId();
        } catch(BaseException exception) {
            throw exception;
        } catch(Exception exception) {
            throw new BaseException("Webhook 事件写入失败",exception);
        }
    }

    @Transactional
    public void publishSpaceMembers(String eventType,Long spaceId,String resourceType,String resourceId,long version,
                                    Long fileId,Map<String,Object> minimal,Map<String,Object> content) {
        for(Long userId : spaceMembers.listActiveUserIds(spaceId)) {
            publish(userId,eventType,resourceType,resourceId,version,fileId,spaceId,minimal,content);
        }
    }

    private Set<String> eventTypes(WebhookSubscription value) {
        return value.getEventTypes() == null || value.getEventTypes().isBlank() ? Set.of()
                : Set.copyOf(Arrays.asList(value.getEventTypes().split(",")));
    }
}
