package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin 全文搜索请求参数。
 */
@Data
public class AdminFullTextSearchDTO {
    @NotBlank(message = "搜索关键词不能为空")
    @Size(max = 200, message = "搜索关键词不能超过 200 个字符")
    private String query;

    /**
     * 可选，指定 Space ID。为空时搜索所有 Space。
     */
    private Long spaceId;

    @Min(value = 1, message = "页码必须大于 0")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量必须大于 0")
    private Integer pageSize = 20;
}
