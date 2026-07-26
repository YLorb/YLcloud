package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MqOutboxRecord {
    private Long id;
    private String messageId;
    private Long taskId;
    private Integer expectedAttemptVersion;
    private String exchangeName;
    private String routingKey;
    private String payloadJson;
    private Integer publishAttempts;
    private LocalDateTime nextPublishAt;
    private String leaseToken;
}
