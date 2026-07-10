package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceRagConfigLogVO {
    private Long id;
    private Long spaceId;
    private Long operatorId;
    private String changedFields;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime createtime;
}
