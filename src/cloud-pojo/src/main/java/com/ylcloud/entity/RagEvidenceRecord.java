package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Immutable server-created citation. IDs supplied by a model are never access credentials. */
@Data
public class RagEvidenceRecord {
    private String id;
    private Long userId;
    private Long spaceId;
    private Long spaceFileId;
    private Long revisionId;
    private Long chunkId;
    private String fileHash;
    private String content;
    private String citationJson;
    private LocalDateTime createdAt;
}
