package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BackupRunVO {
    private Long id;
    private String runKey;
    private String backupType;
    private String status;
    private String archivePath;
    private Long archiveSizeBytes;
    private String archiveHash;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
