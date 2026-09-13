package com.ylcloud.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class SpaceFileImportBatchCreateDTO {
    @NotEmpty(message = "至少选择一个 Personal 文件或目录")
    private List<Long> sourceNodeIds;
    private Long targetParentId;
    @Pattern(regexp = "ATOMIC|SKIP_FAILED",message = "失败策略无效")
    private String failurePolicy = "ATOMIC";
}
