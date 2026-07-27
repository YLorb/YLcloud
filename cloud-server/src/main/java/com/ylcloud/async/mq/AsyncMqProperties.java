package com.ylcloud.async.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ylcloud.async.mq")
public class AsyncMqProperties {
    private boolean enabled;
    private boolean cleanup;
    private boolean maintenance;
    private boolean memory;
    private long publisherDelayMs = 1000;
    private int publisherBatchSize = 50;
    private int publishLeaseSeconds = 30;
    private int retrySeconds = 60;
    private int maxAttempts = 4;
    private int leaseSeconds = 120;
    private int heartbeatSeconds = 30;
    private int retentionDays = 3;
    private int retryQueueTtlMs = 60000;
    private int cleanupConcurrency = 1;
    private int maintenanceConcurrency = 1;
    private int memoryConcurrency = 2;
    private int chatConcurrency = 3;
    private int knowledgeConcurrency = 2;
    private int ragConcurrency = 2;
}
