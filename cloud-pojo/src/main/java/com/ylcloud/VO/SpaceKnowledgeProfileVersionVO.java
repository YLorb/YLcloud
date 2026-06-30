package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceKnowledgeProfileVersionVO {
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
    private String profileSnapshot;
    private String changeSummary;
    private Long createdBy;
    private LocalDateTime createdTime;
}
