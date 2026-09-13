package com.ylcloud.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpaceVersionSettingDTO {
    @NotNull(message = "历史版本开关不能为空")
    @Min(value = 0, message = "历史版本开关只能为 0 或 1")
    @Max(value = 1, message = "历史版本开关只能为 0 或 1")
    private Integer versionEnabled;
}
