package com.ylcloud.VO;

import lombok.Data;

/** The exact excerpt submitted to the model, not the untruncated retrieval candidate. */
@Data
public class RagAnswerEvidence {
    private String evidenceId;
    private Long chunkId;
    private String fileUuid;
    private String fileHash;
    private String contentHash;
    private String content;
}
