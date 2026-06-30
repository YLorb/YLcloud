package com.ylcloud.VO;

import lombok.Data;

import java.util.List;

@Data
public class SpaceKnowledgeProfileDiffVO {
    private Long beforeVersionId;
    private Long afterVersionId;
    private Boolean summaryChanged;
    private String categoryBefore;
    private String categoryAfter;
    private List<String> tagsAdded;
    private List<String> tagsRemoved;
    private List<String> keywordsAdded;
    private List<String> keywordsRemoved;
    private List<String> questionsAdded;
    private List<String> questionsRemoved;
    private String qualityScoreBefore;
    private String qualityScoreAfter;
}
