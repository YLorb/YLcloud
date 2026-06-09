package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分片上传初始化响应结果。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InitifileVO {
    /**
     * 上传任务 ID；非秒传时用于后续上传分片、查询进度和合并分片。
     */
    private String uploadId;

    /**
     * 是否已通过 hash 命中现有文件并完成秒传。
     */
    private Boolean instantUpload;

    /**
     * 已上传的分片序号列表，用于断点续传。
     */
    private List<Integer> uploadedChunks;

    /**
     * 后端确认使用的单分片大小，单位为字节。
     */
    private Long chunkSize;

    /**
     * 后端确认的总分片数。
     */
    private Integer totalChunks;

    /**
     * 秒传成功时返回的文件信息；普通分片上传初始化时为空。
     */
    private FileVO file;
}
