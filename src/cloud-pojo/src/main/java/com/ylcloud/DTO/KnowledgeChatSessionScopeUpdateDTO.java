package com.ylcloud.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeChatSessionScopeUpdateDTO {
    @NotEmpty(message = "至少选择一个知识库")
    @Size(max = 5, message = "一次最多同时查询 5 个知识库")
    private List<Long> spaceIds;
}
