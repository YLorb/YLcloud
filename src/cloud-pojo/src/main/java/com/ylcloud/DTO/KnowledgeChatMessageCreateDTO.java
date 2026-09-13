package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeChatMessageCreateDTO {
    @Pattern(regexp = "^(user|assistant|system)$", message = "对话角色不合法")
    private String role;

    @NotBlank(message = "对话内容不能为空")
    @Size(max = 10000, message = "对话内容不能超过 10000 个字符")
    private String content;

    private String citationsJson;
}
