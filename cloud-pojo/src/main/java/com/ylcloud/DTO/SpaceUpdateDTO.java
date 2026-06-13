package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新空间请求参数。
 */
@Data
public class SpaceUpdateDTO {
    @NotBlank(message = "空间名称不能为空")
    private String name;

    private String description;
}
