package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SpaceKnowledgeDocumentProfileVO {
    private Long id;
    private Long spaceId;
    private Long documentId;
    private Long spaceFileId;
    private String title;
    private String summary;
    private List<String> keywords;
    private List<String> tags;
    private String category;
    private String language;
    private String documentType;
    private BigDecimal qualityScore;
    private String profileStatus;
    private String qualityDetailJson;
    private String qualityIssueJson;
    private BigDecimal scoreBeforeRepair;
    private BigDecimal scoreAfterRepair;
    private String reviewStatus;
    private String reviewReason;
    private Integer sourceChunkCount;
    private Integer sourceCharacterCount;
    private Boolean schemaValid;
    private Integer repairAttempt;
    private String repairReason;
    private Integer profileVersion;
    private Long currentVersionId;
    private Long latestVersionId;
    private String sourceFileHash;
    private String sourceParserVersion;
    private String profileSchemaVersion;
    private String errorMessage;
    private List<String> questions;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
