package com.ylcloud.async.mq;

import com.ylcloud.async.task.TaskErrorSanitizer;
import com.ylcloud.async.task.UnifiedAsyncTaskMapper;
import com.ylcloud.entity.MqOutboxRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    private final UnifiedAsyncTaskMapper mapper = mock(UnifiedAsyncTaskMapper.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AsyncMqProperties properties = new AsyncMqProperties();
    private final OutboxPublisher publisher = new OutboxPublisher(
            mapper,rabbitTemplate,properties,new TaskErrorSanitizer(),new SimpleMeterRegistry()
    );

    @Test
    void initialTaskBecomesClaimableBeforeMessageIsSent() {
        MqOutboxRecord record = record();
        when(mapper.listPublishableIds(any(),anyInt())).thenReturn(List.of(3L));
        when(mapper.claimOutbox(eq(3L),anyString(),anyString(),any(),any())).thenReturn(1);
        when(mapper.getClaimedOutbox(eq(3L),anyString())).thenReturn(record);
        when(mapper.prepareInitialPublish(eq(9L),any())).thenReturn(1);
        doThrow(new IllegalStateException("broker down")).when(rabbitTemplate)
                .send(anyString(),anyString(),any(),any());

        publisher.publishBatch();

        InOrder order = inOrder(mapper,rabbitTemplate);
        order.verify(mapper).prepareInitialPublish(eq(9L),any());
        order.verify(rabbitTemplate).send(anyString(),anyString(),any(),any());
        order.verify(mapper).revertInitialPublish(eq(9L),any());
        order.verify(mapper).releaseOutbox(eq(3L),anyString(),any(),anyString(),any());
    }

    private MqOutboxRecord record() {
        MqOutboxRecord record = new MqOutboxRecord();
        record.setId(3L);
        record.setTaskId(9L);
        record.setMessageId("message-9");
        record.setExchangeName(RabbitTaskTopology.TASK_EXCHANGE);
        record.setRoutingKey("task.maintenance");
        record.setPayloadJson("{}");
        record.setPublishAttempts(1);
        record.setNextPublishAt(LocalDateTime.now());
        return record;
    }
}
