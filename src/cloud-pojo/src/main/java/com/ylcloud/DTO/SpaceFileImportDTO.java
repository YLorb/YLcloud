package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpaceFileImportDTO {
    @NotNull(message = "用户文件 ID 不能为空")
    @Min(value = 1, message = "用户文件 ID 必须大于 0")
    private Long userFileId;

    @Min(value = 0, message = "父目录 ID 不能小于 0")
    private Long parentId;

    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String name;
}
