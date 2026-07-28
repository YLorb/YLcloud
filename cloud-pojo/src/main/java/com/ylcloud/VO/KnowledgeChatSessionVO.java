package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class KnowledgeChatSessionVO {
    private Long id;
    private Long userId;
    private String title;
    private String scopeMode;
    private List<Long> spaceIds;
    private Integer messageCount;
    private List<KnowledgeChatMessageVO> messages;
    private Integer summaryVersion;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
