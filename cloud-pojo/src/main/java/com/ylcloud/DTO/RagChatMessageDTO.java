package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RagChatMessageDTO {
    @Pattern(regexp = "^(user|assistant|system)$", message = "对话角色不合法")
    private String role;

    @NotBlank(message = "对话内容不能为空")
    @Size(max = 2000, message = "单条对话内容不能超过 2000 个字符")
    private String content;
}
