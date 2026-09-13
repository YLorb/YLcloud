package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin 全文搜索结果展示对象。
 */
@Data
public class AdminFullTextSearchResultVO {
    /**
     * 搜索到的文件列表。
     */
    private List<FileHit> files;

    /**
     * 总命中文件数。
     */
    private Long total;

    /**
     * 搜索耗时（毫秒）。
     */
    private Long tookMs;

    /**
     * 单个文件的命中信息。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileHit {
        private Long documentId;
        private Long spaceId;
        private String spaceName;
        private Long spaceFileId;
        private String fileUuid;
        private String fileName;
        private String fileType;
        private String path;
        private Integer chunkCount;
        /**
         * 匹配类型：WORD_MATCH / KEYWORD / VECTOR
         */
        private String matchType;
        /**
         * 检索证据：匹配的 chunk 内容片段。
         */
        private List<ChunkEvidence> evidences;
        private String previewUrl;
        private String downloadUrl;
    }

    /**
     * 检索证据：单条 chunk 片段。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkEvidence {
        private Long chunkId;
        private String content;
        /**
         * 向量相似度分数（仅向量检索时有值）。
         */
        private Float score;
    }
}
