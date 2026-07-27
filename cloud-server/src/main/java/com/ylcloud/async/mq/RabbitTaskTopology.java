package com.ylcloud.async.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "ylcloud.async.mq", name = "enabled", havingValue = "true")
public class RabbitTaskTopology {
    public static final String TASK_EXCHANGE = "ylcloud.task.exchange";
    public static final String RETRY_EXCHANGE = "ylcloud.task.retry.exchange";
    public static final String DLX = "ylcloud.task.dlx";
    public static final String CLEANUP_QUEUE = "ylcloud.task.cleanup.q";
    public static final String MAINTENANCE_QUEUE = "ylcloud.task.maintenance.q";
    public static final String MEMORY_QUEUE = "ylcloud.task.memory.q";
    public static final String CHAT_QUEUE = "ylcloud.task.chat.q";

    @Bean
    public Declarables taskTopology(AsyncMqProperties properties) {
        DirectExchange task = new DirectExchange(TASK_EXCHANGE,true,false);
        DirectExchange retry = new DirectExchange(RETRY_EXCHANGE,true,false);
        DirectExchange dlx = new DirectExchange(DLX,true,false);
        List<Declarable> all = new ArrayList<>(List.of(task,retry,dlx));
        for(String domain : List.of("cleanup","maintenance","memory","chat","knowledge","rag")) {
            String mainName = "ylcloud.task." + domain + ".q";
            String retryName = "ylcloud.task." + domain + ".retry.q";
            String dlqName = "ylcloud.task." + domain + ".dlq";
            String route = "task." + domain;
            Queue main = QueueBuilder.durable(mainName)
                    .withArguments(Map.of(
                            "x-dead-letter-exchange",DLX,
                            "x-dead-letter-routing-key",route
                    )).build();
            Queue retryQueue = QueueBuilder.durable(retryName)
                    .withArguments(Map.of(
                            "x-message-ttl",properties.getRetryQueueTtlMs(),
                            "x-dead-letter-exchange",TASK_EXCHANGE,
                            "x-dead-letter-routing-key",route
                    )).build();
            Queue dlq = QueueBuilder.durable(dlqName).build();
            all.addAll(List.of(
                    main,retryQueue,dlq,
                    BindingBuilder.bind(main).to(task).with(route),
                    BindingBuilder.bind(retryQueue).to(retry).with(route),
                    BindingBuilder.bind(dlq).to(dlx).with(route)
            ));
        }
        return new Declarables(all);
    }

    @Bean
    public Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
