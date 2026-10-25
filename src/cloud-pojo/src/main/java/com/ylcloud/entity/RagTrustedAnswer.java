package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RagTrustedAnswer {
    private String id;
    private Long userId;
    private String content;
    private String answerStatus;
    private String citationsJson;
    private String spaceIdsJson;
    private LocalDateTime createdAt;
}
