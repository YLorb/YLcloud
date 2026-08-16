package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DataExportJob {
    private Long id;
    private Long userId;
    private String jobKey;
    private String status;
    private String exportScope;
    private String encryptionKeyId;
    private String encryptedKey;
    private String encryptedIv;
    private String storagePath;
    private Long fileSizeBytes;
    private String fileHash;
    private String downloadUrl;
    private LocalDateTime downloadExpiresAt;
    private Long asyncTaskId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
