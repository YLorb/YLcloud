package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 文本分块引用实体，对应 space_rag_chunk_ref 表。
 */
@Data
public class SpaceRagChunkRef {
    private Long id;
    private Long spaceId;
    private Long documentId;
    private Long spaceFileId;
    private Long fileChunkId;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
