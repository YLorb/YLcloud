package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.entity.WebhookEvent;
import com.ylcloud.entity.WebhookSubscription;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.WebhookMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookEventServiceTest {
    @Test
    void duplicateBusinessEventReusesEventIdAndDeliveryUniqueness() {
        WebhookMapper mapper = mock(WebhookMapper.class);
        WebhookSubscription subscription = subscription(3L,7L,"FILE_CREATED");
        when(mapper.listActiveSubscriptions(7L)).thenReturn(java.util.List.of(subscription));
        AtomicReference<WebhookEvent> stored = new AtomicReference<>();
        when(mapper.insertEvent(any())).thenAnswer(invocation -> {
            WebhookEvent candidate = invocation.getArgument(0);
            return stored.compareAndSet(null,candidate) ? 1 : 0;
        });
        when(mapper.getEventByKey(anyString())).thenAnswer(invocation -> stored.get());
        WebhookEventService service = new WebhookEventService(mapper,mock(SpaceMemberMapper.class),new ObjectMapper());

        String first = service.publish(7L,"FILE_CREATED","FILE","11",2L,11L,null,
                Map.of("fileId",11L),Map.of("name","private.txt"));
        String duplicate = service.publish(7L,"FILE_CREATED","FILE","11",2L,11L,null,
                Map.of("fileId",11L),Map.of("name","private.txt"));

        assertEquals(first,duplicate);
        verify(mapper,org.mockito.Mockito.times(2)).insertDelivery(eq(first),eq(3L),org.mockito.ArgumentMatchers.any());
        verify(mapper,never()).listActiveSubscriptions(8L);
    }

    @Test
    void acceptsOutOfOrderResourceVersionsAsDistinctEvents() {
        WebhookMapper mapper = mock(WebhookMapper.class);
        when(mapper.insertEvent(any())).thenReturn(1);
        when(mapper.listActiveSubscriptions(7L)).thenReturn(java.util.List.of());
        WebhookEventService service = new WebhookEventService(mapper,mock(SpaceMemberMapper.class),new ObjectMapper());

        String newer = service.publish(7L,"FILE_UPDATED","FILE","11",5L,11L,null,Map.of(),Map.of());
        String older = service.publish(7L,"FILE_UPDATED","FILE","11",4L,11L,null,Map.of(),Map.of());

        assertNotEquals(newer,older);
        verify(mapper,org.mockito.Mockito.times(2)).listActiveSubscriptions(7L);
    }

    private WebhookSubscription subscription(Long id,Long userId,String events) {
        WebhookSubscription value = new WebhookSubscription();
        value.setId(id);
        value.setUserId(userId);
        value.setEventTypes(events);
        value.setStatus("ACTIVE");
        return value;
    }
}
