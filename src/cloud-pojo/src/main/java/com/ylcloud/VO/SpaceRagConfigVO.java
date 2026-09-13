package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 空间 RAG 配置展示对象。
 */
@Data
public class SpaceRagConfigVO {
    private Long id;
    private Long spaceId;
    private String embeddingModel;
    private String chatModel;
    private String vectorCollection;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer topK;
    private BigDecimal temperature;
    private BigDecimal scoreThreshold;
    private Integer enabled;
    private Integer knowledgeProfileEnabled;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
