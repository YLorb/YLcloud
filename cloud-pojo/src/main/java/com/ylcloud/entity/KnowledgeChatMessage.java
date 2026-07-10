package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChatMessage {
    private Long id;
    private Long sessionId;
    private Long userId;
    private String role;
    private String content;
    private String citationsJson;
    private Integer status;
    private LocalDateTime createtime;
}
