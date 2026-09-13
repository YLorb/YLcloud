package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpaceFileRenameDTO {
    @NotBlank(message = "名称不能为空")
    @Size(max = 255, message = "名称不能超过 255 个字符")
    private String name;

    @NotNull(message = "节点版本不能为空")
    @Min(value = 1, message = "节点版本无效")
    private Long expectedVersion;
}
