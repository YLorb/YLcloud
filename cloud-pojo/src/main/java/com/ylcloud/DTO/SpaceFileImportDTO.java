package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 导入个人文件到空间请求参数。
 */
@Data
public class SpaceFileImportDTO {
    @NotNull(message = "用户文件 ID 不能为空")
    private Long userFileId;

    private Long parentId;

    private String name;
}
