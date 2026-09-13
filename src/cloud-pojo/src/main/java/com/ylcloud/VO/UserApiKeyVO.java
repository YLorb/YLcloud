package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class UserApiKeyVO {
    private Long id;
    private String name;
    private String prefix;
    private String driveAccess;
    private Long driveRootFileId;
    private Set<String> scopes;
    private Set<Long> spaceIds;
    private boolean highRiskEnabled;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
