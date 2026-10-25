package com.ylcloud.VO;

import lombok.Data;

import java.util.List;

/**
 * 空间 RAG 查询结果展示对象。
 */
@Data
public class SpaceRagQueryVO {
    private Long spaceId;
    private String spaceName;
    private String question;
    private String answer;
    private String answerId;
    private String answerStatus;
    private String reasonCode;
    private String questionType;
    private List<RagAnswerClaim> claims;
    private List<Long> hitChunkIds;
    private List<String> contexts;
    private List<SpaceRagCitationVO> citations;
    /** Retrieval evidence is retained even when the generation model declines to answer. */
    private List<Long> retrievedChunkIds;
    private Long rewriteDurationMs;
    private Long retrievalDurationMs;
    private Long generationDurationMs;
    private Long totalDurationMs;
}
