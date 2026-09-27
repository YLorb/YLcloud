package com.ylcloud.VO;

import lombok.Data;

@Data
public class SpaceRagCitationVO {
    private Integer index;
    private Long spaceId;
    private String spaceName;
    private Long chunkId;
    private Integer page;
    private Integer offsetStart;
    private Integer offsetEnd;
    private Long documentId;
    private Long spaceFileId;
    private String fileName;
    private String contentSummary;
    private String previewUrl;
    private String downloadUrl;
    private Double vectorScore;
    private Double rerankScore;
}
