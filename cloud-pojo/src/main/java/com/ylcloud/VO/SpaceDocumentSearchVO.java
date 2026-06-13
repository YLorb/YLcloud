package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间文档搜索结果展示对象。
 */
@Data
public class SpaceDocumentSearchVO {
    private Long documentId;
    private Long spaceId;
    private Long spaceFileId;
    private String fileUuid;
    private String fileName;
    private String fileType;
    private String path;
    private String indexStatus;
    private Integer chunkCount;
    private List<String> hitContents;
    private List<Long> hitChunkIds;
    private String previewUrl;
    private String streamUrl;
    private String downloadUrl;
    private LocalDateTime updatetime;
}
