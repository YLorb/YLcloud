package com.ylcloud.DTO;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeChatSessionCreateDTO {
    @Size(max = 120, message = "会话标题不能超过 120 个字符")
    private String title;
    private String scopeMode;
    @Size(max = 20, message = "知识库范围过大")
    private List<Long> spaceIds;
}
