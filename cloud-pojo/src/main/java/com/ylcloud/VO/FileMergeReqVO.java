package com.ylcloud.VO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 分片合并请求参数。
 */
@Data
public class FileMergeReqVO {
    /**
     * 上传任务 ID。
     */
    @NotBlank(message = "上传任务 ID 不能为空")
    private String uploadId;

    /**
     * 合并后最终文件的 UUID。
     */
    private String fileUuid;

    /**
     * 原始文件名。
     */
    private String fileName;

    /**
     * 分片在 MinIO 中的对象名列表，必须按分片序号升序排列。
     */
    @NotEmpty(message = "合并文件信息不能为空")
    private List<String> partNames;
}
