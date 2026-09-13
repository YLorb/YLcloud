package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpaceFolderCreateDTO {
    @NotBlank(message = "目录名称不能为空")
    @Size(max = 255, message = "目录名称不能超过 255 个字符")
    private String name;

    @Min(value = 0, message = "父目录 ID 不能小于 0")
    private Long parentId;
}
