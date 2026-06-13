package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 空间 RAG 查询请求参数。
 */
@Data
public class SpaceRagQueryDTO {
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 个字符")
    private String question;

    private Integer topK;
}
