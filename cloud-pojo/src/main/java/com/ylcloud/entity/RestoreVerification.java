package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RestoreVerification {
    private Long id;
    private Long backupRunId;
    private String verificationKey;
    private String status;
    private String restoreEnvironment;
    private Boolean mysqlRestored;
    private Boolean minioRestored;
    private Boolean qdrantRestored;
    private Boolean configRestored;
    private Boolean businessSampleCheck;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
