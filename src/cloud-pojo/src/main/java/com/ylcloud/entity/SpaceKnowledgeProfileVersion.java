package com.ylcloud.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceKnowledgeProfileVersion {
    private Long id;
    private Long profileId;
    private Long spaceId;
    private Long documentId;
    private Integer versionNo;
    private Long documentVersionId;
    private String sourceType;
    private String modelName;
    private String promptVersion;
    private String schemaVersion;
    private BigDecimal qualityScore;
    private String assetState;
    private BigDecimal confidence;
    private String conflictReason;
    private String sourceFileHash;
    private String sourceParserVersion;
    private String profileSnapshot;
    private String changeSummary;
    private Long createdBy;
    private Long reviewedBy;
    private LocalDateTime activatedAt;
    private LocalDateTime supersededAt;
    private LocalDateTime createdTime;
}
