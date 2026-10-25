package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KnowledgeReindexIntent {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private String fileHash;
    private Long createdBy;
    private String state;
    private Integer attempts;
    private LocalDateTime updatedAt;
}
