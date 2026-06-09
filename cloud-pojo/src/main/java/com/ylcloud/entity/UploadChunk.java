package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已上传分片实体，对应 upload_chunk 表。
 */
@Data
public class UploadChunk {
    /**
     * 自增主键。
     */
    private Long id;

    /**
     * 所属上传任务 ID。
     */
    private String uploadId;

    /**
     * 分片序号，从 0 开始。
     */
    private Integer chunkIndex;

    /**
     * 分片 MD5，用于校验单个分片内容。
     */
    private String chunkMd5;

    /**
     * 分片大小，单位为字节。
     */
    private Long size;

    /**
     * 分片在 MinIO 中的对象名。
     */
    private String objectName;

    /**
     * 分片状态：1 有效，0 无效。
     */
    private Integer status;

    /**
     * 创建时间。
     */
    private LocalDateTime createtime;

    /**
     * 更新时间。
     */
    private LocalDateTime updatetime;
}
