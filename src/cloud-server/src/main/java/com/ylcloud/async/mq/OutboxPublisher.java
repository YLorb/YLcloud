package com.ylcloud.async.mq;

import com.ylcloud.async.task.TaskErrorSanitizer;
import com.ylcloud.async.task.UnifiedAsyncTaskMapper;
import com.ylcloud.entity.MqOutboxRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "ylcloud.async.mq", name = "enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final UnifiedAsyncTaskMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final AsyncMqProperties properties;
    private final TaskErrorSanitizer sanitizer;
    private final MeterRegistry meterRegistry;
    private final String publisherId = "publisher-" + UUID.randomUUID();

    public OutboxPublisher(UnifiedAsyncTaskMapper mapper,
                           RabbitTemplate rabbitTemplate,
                           AsyncMqProperties properties,
                           TaskErrorSanitizer sanitizer,
                           MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${ylcloud.async.mq.publisher-delay-ms:1000}")
    public void publishBatch() {
        LocalDateTime now = LocalDateTime.now();
        for(Long id : mapper.listPublishableIds(now,properties.getPublisherBatchSize())) publishOne(id);
    }

    public void publishOne(Long id) {
        String leaseToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if(mapper.claimOutbox(id,leaseToken,publisherId,now.plusSeconds(properties.getPublishLeaseSeconds()),now) != 1) return;
        MqOutboxRecord record = mapper.getClaimedOutbox(id,leaseToken);
        if(record == null) return;
        boolean initialPrepared = mapper.prepareInitialPublish(record.getTaskId(),now) == 1;
        try {
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            messageProperties.setMessageId(record.getMessageId());
            Message message = new Message(record.getPayloadJson().getBytes(StandardCharsets.UTF_8),messageProperties);
            CorrelationData correlation = new CorrelationData(record.getMessageId());
            rabbitTemplate.send(record.getExchangeName(),record.getRoutingKey(),message,correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(correlation.getReturned() != null
                        ? "RabbitMQ returned unroutable message"
                        : "RabbitMQ publish NACK: " + confirm.getReason());
            }
            LocalDateTime confirmedAt = LocalDateTime.now();
            mapper.markOutboxSent(id,leaseToken,confirmedAt);
            meterRegistry.counter("ylcloud.async.outbox.published").increment();
        } catch(Exception error) {
            if(initialPrepared) mapper.revertInitialPublish(record.getTaskId(),LocalDateTime.now());
            String safe = sanitizer.sanitize(error.getMessage());
            mapper.releaseOutbox(id,leaseToken,LocalDateTime.now().plusSeconds(backoff(record.getPublishAttempts())),
                    safe,LocalDateTime.now());
            meterRegistry.counter("ylcloud.async.outbox.failed").increment();
            log.warn("Outbox publish deferred: outboxId={}, taskId={}, reason={}",id,record.getTaskId(),safe);
        }
    }

    private long backoff(Integer attempts) {
        int count = attempts == null ? 1 : Math.max(1,attempts);
        return Math.min(60L,1L << Math.min(5,count - 1));
    }
}
