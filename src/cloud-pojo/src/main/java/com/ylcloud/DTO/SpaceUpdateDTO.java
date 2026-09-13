package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpaceUpdateDTO {
    @NotBlank(message = "空间名称不能为空")
    @Size(max = 100, message = "空间名称不能超过 100 个字符")
    private String name;

    @Size(max = 500, message = "空间描述不能超过 500 个字符")
    private String description;
}
