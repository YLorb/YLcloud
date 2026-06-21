package com.ylcloud.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 空间 RAG 查询请求参数。
 */
@Data
public class SpaceRagQueryDTO {
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 个字符")
    private String question;

    private Integer topK;

    @Valid
    @Size(max = 10, message = "对话历史不能超过 10 条")
    private List<RagChatMessageDTO> history;
}
