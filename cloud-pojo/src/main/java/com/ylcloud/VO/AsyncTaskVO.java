package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户可见的真实后台任务统一展示对象。
 */
@Data
public class AsyncTaskVO {
    private String id;
    private Long taskId;
    private Long spaceId;
    private Long documentId;
    private String source;
    private String taskDomain;
    private String type;
    private String title;
    private String status;
    private String phase;
    private Integer progress;
    private Integer total;
    private Integer current;
    private String message;
    private String errorMessage;
    private Boolean retryable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
