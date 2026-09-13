package com.ylcloud.DTO;

import lombok.Data;

/**
 * 单分片上传请求参数。
 */
@Data
public class ChunkUploadDTO {
    /**
     * 上传任务 ID。
     */
    private String uploadId;

    /**
     * 当前分片序号，从 0 开始。
     */
    private Integer chunkIndex;

    /**
     * 当前分片 MD5，可为空；传入时后端会进行校验。
     */
    private String chunkMd5;
}
