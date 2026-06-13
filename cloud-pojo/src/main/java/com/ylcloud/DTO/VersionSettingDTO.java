package com.ylcloud.DTO;

import lombok.Data;

/**
 * 文件历史版本开关请求参数。
 * <p>
 * 用于单个空间文件时允许传入 null，表示继承空间默认设置。
 */
@Data
public class VersionSettingDTO {
    private Integer versionEnabled;
}
