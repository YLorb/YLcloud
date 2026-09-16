package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeploymentSecretUpdateDTO(
        @NotBlank(message = "Secret 不能为空")
        @Size(max = 16384, message = "Secret 长度不能超过 16384 个字符")
        String value
) {
}
