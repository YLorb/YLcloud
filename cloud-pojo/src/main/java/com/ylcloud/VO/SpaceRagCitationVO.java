package com.ylcloud.VO;

import lombok.Data;

@Data
public class SpaceRagCitationVO {
    private Integer index;
    private Long chunkId;
    private Long documentId;
    private Long spaceFileId;
    private String fileName;
    private String contentSummary;
    private String previewUrl;
    private String downloadUrl;
}
