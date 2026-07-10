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
    private List<Long> hitChunkIds;
    private List<String> contexts;
    private List<SpaceRagCitationVO> citations;
}
