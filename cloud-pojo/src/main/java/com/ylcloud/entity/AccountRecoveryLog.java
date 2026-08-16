package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AccountRecoveryLog {
    private Long id;
    private Long userId;
    private Long recoveredBy;
    private String previousStatus;
    private String recoveryReason;
    private LocalDateTime createdAt;
}
