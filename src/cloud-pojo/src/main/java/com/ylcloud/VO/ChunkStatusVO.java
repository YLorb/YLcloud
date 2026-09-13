package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分片上传进度查询响应结果。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChunkStatusVO {
    /**
     * 上传任务 ID。
     */
    private String uploadId;

    /**
     * 该文件总分片数。
     */
    private Integer totalChunks;

    /**
     * 已成功上传的分片数量。
     */
    private Integer uploadedCount;

    /**
     * 已成功上传的分片序号列表。
     */
    private List<Integer> uploadedChunks;
}
