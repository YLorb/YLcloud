package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 空间文档搜索请求参数。
 */
@Data
public class SpaceDocumentSearchDTO {
    private String keyword;
    private String fileType;
    private String indexStatus;
    private Integer searchContent = 1;

    @Min(value = 1, message = "页码必须大于 0")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量必须大于 0")
    private Integer pageSize = 20;
}
