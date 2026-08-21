package com.ylcloud.entity;

import lombok.Data;

/**
 * Admin 全文搜索 chunk 命中结果（扁平化查询结果）。
 */
@Data
public class AdminChunkSearchHit {
    private Long chunkId;
    private String content;
    private String fileUuid;
    private Long documentId;
    private Long spaceId;
    private Long spaceFileId;
    private String fileName;
    private String fileType;
    private Integer chunkCount;
}
