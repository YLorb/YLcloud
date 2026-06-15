package com.ylcloud.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class VersionSettingDTO {
    @Min(value = 0, message = "文件历史版本开关只能为 0、1 或 null")
    @Max(value = 1, message = "文件历史版本开关只能为 0、1 或 null")
    private Integer versionEnabled;
}
