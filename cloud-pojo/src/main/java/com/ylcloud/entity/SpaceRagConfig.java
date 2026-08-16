package com.ylcloud.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 空间 RAG 配置实体，对应 space_rag_config 表。
 */
@Data
public class SpaceRagConfig {
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
