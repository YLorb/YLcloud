package com.ylcloud.workflow.tool;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkflowConfirmationRecord {
    private String grantId;
    private Long userId;
    private String toolName;
    private String parameterHash;
    private String grantMode;
    private String similarityScopeJson;
    private LocalDateTime expiresAt;
    private Boolean revoked;
    private String consumedInvocationId;
}
