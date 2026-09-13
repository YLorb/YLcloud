package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WebhookEvent {
    private String eventId;
    private String eventKey;
    private Long userId;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private Long resourceVersion;
    private Long fileId;
    private Long spaceId;
    private String minimalPayloadJson;
    private String contentPayloadJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createTime;
}
