package com.ylcloud.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceKnowledgeDocumentProfile {
    private Long id;
    private Long spaceId;
    private Long documentId;
    private Long spaceFileId;
    private String title;
    private String summary;
    private String keywordsJson;
    private String tagsJson;
    private String category;
    private String language;
    private String documentType;
    private BigDecimal qualityScore;
    private String profileStatus;
    private String rawLlmOutput;
    private String normalizedProfileJson;
    private String qualityDetailJson;
    private String qualityIssueJson;
    private BigDecimal scoreBeforeRepair;
    private BigDecimal scoreAfterRepair;
    private String reviewStatus;
    private String reviewReason;
    private String sourceChunkIds;
    private Integer sourceChunkCount;
    private Integer sourceCharacterCount;
    private String sourceSnapshotSignature;
    private Long sourceSnapshotRevision;
    private Boolean schemaValid;
    private Integer repairAttempt;
    private String repairReason;
    private Integer profileVersion;
    private Long currentVersionId;
    private Long latestVersionId;
    private String latestAssetState;
    private BigDecimal latestConfidence;
    private String latestConflictReason;
    private String sourceFileHash;
    private String sourceParserVersion;
    private String profileSchemaVersion;
    private String errorMessage;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
