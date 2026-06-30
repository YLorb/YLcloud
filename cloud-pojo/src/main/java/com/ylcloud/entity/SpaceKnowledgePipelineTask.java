package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceKnowledgePipelineTask {
    private Long id;
    private Long spaceId;
    private Long documentId;
    private String taskType;
    private String taskStatus;
    private String stage;
    private Integer progress;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String errorMessage;
    private Boolean forceRebuild;
    private String terminalStage;
    private String terminalReason;
    private String incrementalAction;
    private String incrementalDetail;
    private Long createdBy;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
