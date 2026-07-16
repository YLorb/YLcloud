package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChatMessageVO {
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String citationsJson;
    private String taskStatus;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
