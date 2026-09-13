package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SpaceKnowledgeDashboardVO {
    private Long spaceId;
    private Integer documentCount;
    private Integer indexedCount;
    private Integer profiledCount;
    private Integer failedProfileCount;
    private Integer needsReviewCount;
    private BigDecimal averageQualityScore;
    private Integer categoryCount;
    private Integer tagCount;
    private Integer pendingTaskCount;
    private Integer runningTaskCount;
    private Integer failedTaskCount;
    private List<SpaceKnowledgeFacetVO> categories;
    private List<SpaceKnowledgeFacetVO> tags;
    private List<SpaceKnowledgePipelineTaskVO> recentFailedTasks;
}
