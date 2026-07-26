package com.ylcloud.async.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitTaskTopologyTest {
    @Test
    void declaresAllSixDurableMainRetryAndDeadLetterQueues() {
        AsyncMqProperties properties = new AsyncMqProperties();
        properties.setRetryQueueTtlMs(60000);
        Declarables declarations = new RabbitTaskTopology().taskTopology(properties);

        assertEquals(39,declarations.getDeclarables().size());
        long queues = declarations.getDeclarables().stream().filter(Queue.class::isInstance).count();
        assertEquals(18,queues);
        assertTrue(declarations.getDeclarables().stream().filter(Queue.class::isInstance)
                .map(Queue.class::cast).allMatch(Queue::isDurable));
    }
}
