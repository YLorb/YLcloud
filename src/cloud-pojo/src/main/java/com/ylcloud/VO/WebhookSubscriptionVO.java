package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class WebhookSubscriptionVO {
    private Long id;
    private String name;
    private String targetUrl;
    private Long apiKeyId;
    private Set<String> eventTypes;
    private Boolean includeContent;
    private String status;
    private LocalDateTime previousSecretValidUntil;
    private LocalDateTime lastDeliveryAt;
    private LocalDateTime createTime;
}
