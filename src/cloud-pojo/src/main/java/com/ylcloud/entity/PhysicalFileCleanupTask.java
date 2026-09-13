package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PhysicalFileCleanupTask {
    private Long id;
    private String fileUuid;
    private String taskStatus;
    private Integer retryCount;
    private String errorMessage;
    private Long asyncTaskId;
    private Long resourceVersion;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
