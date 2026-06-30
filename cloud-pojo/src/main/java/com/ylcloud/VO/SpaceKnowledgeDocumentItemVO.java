package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SpaceKnowledgeDocumentItemVO {
    private Long documentId;
    private Long spaceId;
    private Long spaceFileId;
    private String fileUuid;
    private String fileName;
    private String fileType;
    private String indexStatus;
    private Integer chunkCount;
    private String profileStatus;
    private String title;
    private String summary;
    private String category;
    private List<String> tags;
    private List<String> keywords;
    private BigDecimal qualityScore;
    private String reviewStatus;
    private String reviewReason;
    private String qualityIssueJson;
    private Integer sourceChunkCount;
    private Integer repairAttempt;
    private String errorMessage;
    private LocalDateTime updatetime;
}
