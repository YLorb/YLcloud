package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceDissolutionEvent {
    private Long id;
    private String eventId;
    private Long spaceId;
    private Long ownerId;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String errorMessage;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
