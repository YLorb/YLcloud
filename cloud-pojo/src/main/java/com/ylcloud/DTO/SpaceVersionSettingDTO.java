package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 空间历史版本开关请求参数。
 */
@Data
public class SpaceVersionSettingDTO {
    @NotNull(message = "历史版本开关不能为空")
    private Integer versionEnabled;
}
