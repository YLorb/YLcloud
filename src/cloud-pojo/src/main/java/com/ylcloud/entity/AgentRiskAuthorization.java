package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRiskAuthorization {
    private Long id;
    private Long userId;
    private Long apiKeyId;
    private String authorizationMode;
    private String authorizationStatus;
    private LocalDateTime expiresAt;
    private String consumedInvocationId;
    private LocalDateTime consumedAt;
    private Long revokedBy;
    private LocalDateTime revokedAt;
    private Boolean riskAcknowledged;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
