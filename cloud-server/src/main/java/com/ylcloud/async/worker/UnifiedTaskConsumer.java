package com.ylcloud.async.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.ylcloud.async.TaskDispatchEnvelope;
import com.ylcloud.async.mq.RabbitTaskTopology;
import com.ylcloud.async.task.*;
import com.ylcloud.entity.UnifiedAsyncTask;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ylcloud.async.mq", name = "enabled", havingValue = "true")
public class UnifiedTaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(UnifiedTaskConsumer.class);
    private final ObjectMapper objectMapper;
    private final UnifiedAsyncTaskMapper mapper;
    private final UnifiedTaskCenterService taskCenter;
    private final TaskHandlerRegistry registry;
    private final TaskFailureClassifier classifier;
    private final MeterRegistry meterRegistry;
    private final String workerId = "worker-" + UUID.randomUUID();

    public UnifiedTaskConsumer(ObjectMapper objectMapper,
                               UnifiedAsyncTaskMapper mapper,
                               UnifiedTaskCenterService taskCenter,
                               TaskHandlerRegistry registry,
                               TaskFailureClassifier classifier,
                               MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.taskCenter = taskCenter;
        this.registry = registry;
        this.classifier = classifier;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = RabbitTaskTopology.CLEANUP_QUEUE,
            concurrency = "${ylcloud.async.mq.cleanup-concurrency:1}")
    public void consumeCleanup(Message message, Channel channel) throws Exception {
        consume(message,channel);
    }

    @RabbitListener(queues = RabbitTaskTopology.MAINTENANCE_QUEUE,
            concurrency = "${ylcloud.async.mq.maintenance-concurrency:1}")
    public void consumeMaintenance(Message message, Channel channel) throws Exception {
        consume(message,channel);
    }

    @RabbitListener(queues = RabbitTaskTopology.MEMORY_QUEUE,
            concurrency = "${ylcloud.async.mq.memory-concurrency:2}")
    public void consumeMemory(Message message,Channel channel) throws Exception {
        consume(message,channel);
    }

    @RabbitListener(queues = RabbitTaskTopology.CHAT_QUEUE,
            concurrency = "${ylcloud.async.mq.chat-concurrency:3}")
    public void consumeChat(Message message,Channel channel) throws Exception {
        consume(message,channel);
    }

    @RabbitListener(queues = RabbitTaskTopology.KNOWLEDGE_QUEUE,
            concurrency = "${ylcloud.async.mq.knowledge-concurrency:2}")
    public void consumeKnowledge(Message message,Channel channel) throws Exception {
        consume(message,channel);
    }

    @RabbitListener(queues = RabbitTaskTopology.RAG_QUEUE,
            concurrency = "${ylcloud.async.mq.rag-concurrency:2}")
    public void consumeRag(Message message,Channel channel) throws Exception {
        consume(message,channel);
    }

    private void consume(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        TaskDispatchEnvelope envelope;
        try {
            envelope = objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    TaskDispatchEnvelope.class);
            if(envelope.schemaVersion() != 1 || !"TASK_DISPATCH".equals(envelope.eventType())) {
                throw new IllegalArgumentException("unsupported task envelope");
            }
        } catch(Exception protocolError) {
            meterRegistry.counter("ylcloud.async.consumer.dead").increment();
            channel.basicReject(tag,false);
            return;
        }

        try {
            boolean firstDelivery = mapper.insertInbox(envelope.messageId(),envelope.taskId(),
                    envelope.expectedAttemptVersion(),workerId, LocalDateTime.now()) == 1;
            String leaseToken = UUID.randomUUID().toString();
            UnifiedAsyncTask task = taskCenter.claim(envelope,workerId,leaseToken);
            if(task == null) {
                mapper.finishInbox(envelope.messageId(),"IGNORED",
                        firstDelivery ? "任务状态不可领取" : "重复消息",LocalDateTime.now());
                channel.basicAck(tag,false);
                meterRegistry.counter("ylcloud.async.consumer.ignored").increment();
                return;
            }
            try {
                Object result = registry.require(task.getTaskType())
                        .execute(task,new TaskExecutionContext(taskCenter,task,leaseToken));
                if(!taskCenter.complete(task,leaseToken,result)) {
                    log.info("Stale worker result rejected: taskId={}, attempt={}",task.getId(),task.getAttemptVersion());
                }
            } catch(TaskCanceledException canceled) {
                log.info("Task cooperatively canceled: taskId={}",task.getId());
            } catch(StaleWorkerException staleWorker) {
                log.info("Stale worker stopped: taskId={}, attempt={}",task.getId(),task.getAttemptVersion());
            } catch(Exception businessError) {
                log.warn("Task execution failed: taskId={}, taskType={}, attempt={}",
                        task.getId(),task.getTaskType(),task.getAttemptVersion(),businessError);
                taskCenter.fail(task,leaseToken,classifier.classify(businessError));
            }
            mapper.finishInbox(envelope.messageId(),"PROCESSED","任务消息已处理",LocalDateTime.now());
            channel.basicAck(tag,false);
            meterRegistry.counter("ylcloud.async.consumer.processed").increment();
        } catch(Exception infrastructureError) {
            log.error("Task delivery transaction failed: taskId={}, messageId={}",
                    envelope.taskId(),envelope.messageId(),infrastructureError);
            channel.basicNack(tag,false,true);
        }
    }
}
