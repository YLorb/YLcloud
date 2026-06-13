package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建空间请求参数。
 */
@Data
public class SpaceCreateDTO {
    @NotBlank(message = "空间名称不能为空")
    private String name;

    private String description;
}
