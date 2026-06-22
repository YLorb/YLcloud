package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 配置变更日志实体。
 */
@Data
public class SpaceRagConfigLog {
    private Long id;
    private Long spaceId;
    private Long operatorId;
    private String changedFields;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime createtime;
}
