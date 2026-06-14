package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 索引任务实体，对应 space_rag_task 表。
 */
@Data
public class SpaceRagTask {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private Long documentId;
    private String taskType;
    private String taskStatus;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
