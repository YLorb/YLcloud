package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物理文件 RAG 文本分块实体，对应 file_rag_chunk 表。
 */
@Data
public class FileRagChunk {
    private Long id;
    private String fileUuid;
    private String fileHash;
    private Integer chunkIndex;
    private String content;
    private String contentHash;
    private Integer tokenCount;
    private String metadata;
    private String vectorId;
    private String embeddingModel;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
