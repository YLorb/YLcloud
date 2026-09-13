package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KnowledgeChatEpisodeVO {
    private Long id;
    private Integer episodeNo;
    private Long startSequenceNo;
    private Long endSequenceNo;
    private String title;
    private String summary;
    private Integer messageCount;
    private LocalDateTime updatetime;
}
