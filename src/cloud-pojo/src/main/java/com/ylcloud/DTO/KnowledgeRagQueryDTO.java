package com.ylcloud.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeRagQueryDTO {
    @NotEmpty(message = "至少选择一个知识库")
    @Size(max = 5, message = "一次最多同时查询 5 个知识库")
    private List<Long> spaceIds;

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 个字符")
    private String question;

    private String retrievalMode;

    @Valid
    @Size(max = 10, message = "对话历史不能超过 10 条")
    private List<RagChatMessageDTO> history;
}
