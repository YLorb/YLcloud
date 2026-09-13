package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间 RAG 文档索引展示对象。
 */
@Data
public class SpaceRagDocumentVO {
    private Long id;
    private Long spaceId;
    private Long spaceFileId;
    private String fileUuid;
    private String fileName;
    private String fileHash;
    private String fileType;
    private String indexStatus;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
