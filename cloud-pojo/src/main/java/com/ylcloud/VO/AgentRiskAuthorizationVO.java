package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRiskAuthorizationVO {
    private Long id;
    private String subjectType;
    private Long apiKeyId;
    private String mode;
    private String status;
    private LocalDateTime expiresAt;
    private String consumedInvocationId;
    private LocalDateTime consumedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
