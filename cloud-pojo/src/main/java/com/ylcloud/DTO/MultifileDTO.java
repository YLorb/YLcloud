package com.ylcloud.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MultifileDTO {
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String fileName;

    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "文件 MD5 格式不正确")
    private String fileMd5;

    @NotBlank(message = "文件 hash 不能为空")
    @Size(max = 128, message = "文件 hash 不能超过 128 个字符")
    private String fileHash;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于 0")
    private Long fileSize;

    @Min(value = 1, message = "分片大小必须大于 0")
    private Long chunkSize;

    @Min(value = 1, message = "分片数量必须大于 0")
    @Max(value = 10000, message = "分片数量过大")
    private Integer totalChunks;

    @Min(value = 0, message = "父目录 ID 不能小于 0")
    private Long parentId;
}
