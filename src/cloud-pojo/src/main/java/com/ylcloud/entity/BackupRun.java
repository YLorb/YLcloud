package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BackupRun {
    private Long id;
    private String runKey;
    private String backupType;
    private String status;
    private String manifestJson;
    private String mysqlDumpPath;
    private String minioSnapshotPath;
    private String qdrantSnapshotPath;
    private String configSnapshotPath;
    private String archivePath;
    private Long archiveSizeBytes;
    private String archiveHash;
    private String encryptionKeyId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
