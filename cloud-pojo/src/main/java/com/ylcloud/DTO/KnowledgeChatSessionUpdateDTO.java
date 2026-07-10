package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeChatSessionUpdateDTO {
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 120, message = "会话标题不能超过 120 个字符")
    private String title;
}
