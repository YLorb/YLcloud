package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceFileDeleteBatch {
    private Long id;
    private String batchKey;
    private Long spaceId;
    private Long rootFileId;
    private Long rootNodeVersion;
    private String subtreeDigest;
    private Integer folderCount;
    private Integer fileCount;
    private Integer knowledgeCount;
    private String batchStatus;
    private Integer processedCount;
    private String errorMessage;
    private Long asyncTaskId;
    private Long createdBy;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
