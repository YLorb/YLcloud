package com.ylcloud.VO;

import lombok.Data;

/**
 * 空间文档搜索命中的文本块。
 */
@Data
public class SpaceDocumentChunkHitVO {
    private Long documentId;
    private Long chunkId;
    private String content;
}
