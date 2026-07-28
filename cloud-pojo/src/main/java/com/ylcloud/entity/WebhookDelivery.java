package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WebhookDelivery {
    private Long id;
    private String eventId;
    private Long subscriptionId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private Integer responseStatus;
    private String lastError;
    private LocalDateTime deliveredAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
