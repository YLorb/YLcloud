package com.ylcloud.VO;

import lombok.Data;

@Data
public class SpaceRagCitationVO {
    private Integer index;
    private Long spaceId;
    private String spaceName;
    private Long chunkId;
    private Long documentId;
    private Long spaceFileId;
    private String fileName;
    private String contentSummary;
    private String previewUrl;
    private String downloadUrl;
    private Double vectorScore;
    private Double rerankScore;
    private String evidenceId;
    private String sourceEvidenceId;
    private Long revisionId;
    private Long fileVersionId;
    private String fileHash;
    private Integer pageStart;
    private Integer pageEnd;
    private String locatorStatus;
    private String heading;
    private Boolean stale;
}
