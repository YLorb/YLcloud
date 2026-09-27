package com.ylcloud.VO;

import lombok.Data;

/** A retrieved chunk's location in the parser's fullText. End is exclusive. */
@Data
public class SpaceRagEvidenceVO {
    private Long chunkId;
    private Integer page;
    private Integer offsetStart;
    private Integer offsetEnd;
}
