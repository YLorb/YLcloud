package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 问答日志实体，对应 space_rag_query_log 表。
 */
@Data
public class SpaceRagQueryLog {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String question;
    private String answer;
    private String hitChunkIds;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer success;
    private String errorMessage;
    private LocalDateTime createtime;
}
