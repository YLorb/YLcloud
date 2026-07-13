package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片上传任务实体，对应 upload_task 表。
 */
@Data
public class UploadTask {
    /**
     * 自增主键。
     */
    private Long id;

    /**
     * 上传任务唯一标识。
     */
    private String uploadId;

    /** 用户、父目录和规范化完整文件名组成的占用键哈希。 */
    private String fileKey;

    /**
     * 发起上传的用户 ID。
     */
    private Long userId;

    /**
     * 目标父目录 ID。
     */
    private Long parentId;

    /**
     * 原始文件名。
     */
    private String fileName;

    /**
     * 完整文件大小，单位为字节。
     */
    private Long fileSize;

    /**
     * 完整文件 MD5。
     */
    private String fileMd5;

    private String fileSha1;

    /**
     * 完整文件内容 hash，用于秒传。
     */
    private String fileHash;

    /**
     * 单个分片大小，单位为字节。
     */
    private Long chunkSize;

    /**
     * 总分片数。
     */
    private Integer totalChunks;

    /**
     * 已上传分片数。
     */
    private Integer uploadedChunks;

    /**
     * 上传任务状态：1 上传中，2 已合并，3 已取消，4 失败。
     */
    private Integer status;

    /**
     * 合并成功后的文件 UUID。
     */
    private String fileUuid;

    private LocalDateTime lastActivityTime;

    private LocalDateTime mergeStartedTime;

    /**
     * 创建时间。
     */
    private LocalDateTime createtime;

    /**
     * 更新时间。
     */
    private LocalDateTime updatetime;
}
