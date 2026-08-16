package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DataExportJobVO {
    private Long id;
    private String status;
    private String exportScope;
    private Long fileSizeBytes;
    private String downloadUrl;
    private LocalDateTime downloadExpiresAt;
    private String decryptionKey;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
