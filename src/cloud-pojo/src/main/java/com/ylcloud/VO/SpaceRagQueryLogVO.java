package com.ylcloud.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SpaceRagQueryLogVO {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String question;
    private String answer;
    private String hitChunkIds;
    private String modelName;
    private Integer topK;
    private BigDecimal temperature;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer success;
    private String errorMessage;
    private Integer citationCount;
    private LocalDateTime createtime;
}
