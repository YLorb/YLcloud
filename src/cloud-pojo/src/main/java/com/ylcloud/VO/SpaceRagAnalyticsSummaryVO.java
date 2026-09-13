package com.ylcloud.VO;

import lombok.Data;

@Data
public class SpaceRagAnalyticsSummaryVO {
    private Long spaceId;
    private Integer queryCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer noAnswerCount;
    private Integer citedQueryCount;
    private Double citationCoverage;
}
