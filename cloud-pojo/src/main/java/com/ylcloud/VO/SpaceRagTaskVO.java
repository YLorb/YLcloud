package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 索引任务展示对象。
 */
@Data
public class SpaceRagTaskVO {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private Long documentId;
    private String taskType;
    private String taskStatus;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
