package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceKnowledgePipelineEventVO {
    private Long id;
    private Long taskId;
    private Long spaceId;
    private Long documentId;
    private String stage;
    private String eventType;
    private String eventStatus;
    private String message;
    private String inputSummary;
    private String outputSummary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime eventTime;
    private Long durationMs;
    private String traceId;
    private Integer attemptNo;
    private LocalDateTime createdAt;
}
