package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CrossStoreOperation {
    private Long id;
    private String operationKey;
    private String operationType;
    private String operationStatus;
    private String payloadHash;
    private String resourceId;
    private String externalRef;
    private String resultRef;
    private Integer attemptCount;
    private LocalDateTime leaseUntil;
    private String errorMessage;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
