package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpaceFileMoveDTO {
    @NotNull(message = "目标目录不能为空")
    @Min(value = 1, message = "目标目录无效")
    private Long targetParentId;

    @NotNull(message = "节点版本不能为空")
    @Min(value = 1, message = "节点版本无效")
    private Long expectedVersion;
}
