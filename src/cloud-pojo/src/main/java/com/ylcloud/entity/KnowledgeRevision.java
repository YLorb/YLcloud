package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Immutable published knowledge snapshot. Access is decided by current node/configuration. */
@Data
public class KnowledgeRevision {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private Long documentId;
    private String fileUuid;
    private String fileHash;
    private Long fileVersionId;
    private String minioVersionId;
    private String manifestHash;
    private LocalDateTime publishedAt;
    private LocalDateTime supersededAt;
    private LocalDateTime retainedUntil;
}
