package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChatSession {
    private Long id;
    private Long userId;
    private String title;
    private String scopeMode;
    private String scopeSpaceIds;
    private Long nextSequenceNo;
    private String rollingSummary;
    private Long summaryUptoSequenceNo;
    private Integer summaryVersion;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
