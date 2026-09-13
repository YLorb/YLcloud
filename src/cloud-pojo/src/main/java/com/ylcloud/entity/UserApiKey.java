package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserApiKey {
    private Long id;
    private Long userId;
    private String keyName;
    private String keyPrefix;
    private String keyHash;
    private String driveAccess;
    private Long driveRootFileId;
    private String keyStatus;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
