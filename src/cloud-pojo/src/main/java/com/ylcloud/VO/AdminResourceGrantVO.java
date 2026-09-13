package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminResourceGrantVO {
    private Long id;
    private Long grantorId;
    private Long adminUserId;
    private String resourceType;
    private Long resourceId;
    private String action;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
