package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WebhookSubscription {
    private Long id;
    private Long userId;
    private Long apiKeyId;
    private String name;
    private String targetUrl;
    private String eventTypes;
    private Boolean includeContent;
    private String status;
    private String secretCipher;
    private String previousSecretCipher;
    private LocalDateTime previousSecretValidUntil;
    private LocalDateTime lastDeliveryAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
