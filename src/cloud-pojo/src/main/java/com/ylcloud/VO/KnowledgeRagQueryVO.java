package com.ylcloud.VO;

import lombok.Data;

import java.util.List;

@Data
public class KnowledgeRagQueryVO {
    private String question;
    private String answer;
    private List<Long> spaceIds;
    private List<SpaceRagQueryVO> results;
    private List<SpaceRagCitationVO> citations;
}
