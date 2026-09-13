package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 文档索引实体，对应 space_rag_document 表。
 */
@Data
public class SpaceRagDocument {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private String fileUuid;
    private String fileName;
    private String fileHash;
    private String fileType;
    private String indexStatus;
    private String vectorState;
    private Long consistencyVersion;
    private Long consistencyAsyncTaskId;
    private Integer chunkCount;
    private String errorMessage;
    private Long createdBy;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
