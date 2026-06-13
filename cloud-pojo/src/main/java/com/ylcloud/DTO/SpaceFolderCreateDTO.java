package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建空间目录请求参数。
 */
@Data
public class SpaceFolderCreateDTO {
    @NotBlank(message = "目录名称不能为空")
    private String name;

    private Long parentId;
}
