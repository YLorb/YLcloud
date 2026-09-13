package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 空间网页链接导入请求参数。
 */
@Data
public class SpaceWebLinkImportDTO {
    @NotBlank(message = "网页链接不能为空")
    @Size(max = 2048, message = "网页链接不能超过 2048 个字符")
    private String url;

    @Min(value = 0, message = "父目录 ID 不能小于 0")
    private Long parentId;

    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String name;
}
