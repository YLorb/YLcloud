package com.ylcloud.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceKnowledgeQuestion {
    private Long id;
    private Long spaceId;
    private Long documentId;
    private String question;
    private String source;
    private BigDecimal confidence;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
