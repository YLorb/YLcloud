package com.ylcloud.DTO;

import lombok.Data;

/**
 * 分片上传初始化请求参数。
 */
@Data
public class MultifileDTO {
    /**
     * 原始文件名。
     */
    private String fileName;

    /**
     * 完整文件的 MD5 值，用于最终文件校验。
     */
    private String fileMd5;

    /**
     * 完整文件的内容 hash，用于秒传判断。
     */
    private String fileHash;

    /**
     * 完整文件大小，单位为字节。
     */
    private Long fileSize;

    /**
     * 单个分片大小，单位为字节；为空时后端使用默认分片大小。
     */
    private Long chunkSize;

    /**
     * 文件总分片数；为空时后端根据文件大小和分片大小计算。
     */
    private Integer totalChunks;

    /**
     * 文件上传到的父目录 ID；为空或 0 时表示当前用户根目录。
     */
    private Long parentId;
}
