package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新空间 RAG 配置的请求参数。
 */
@Data
public class SpaceRagConfigUpdateDTO {
    private String embeddingModel;
    private String chatModel;

    @Min(value = 1, message = "文本分块大小必须大于 0")
    private Integer chunkSize;

    @Min(value = 0, message = "文本分块重叠长度不能小于 0")
    private Integer chunkOverlap;

    @Min(value = 1, message = "召回数量必须大于 0")
    private Integer topK;

    @DecimalMin(value = "0.0", message = "温度不能小于 0")
    @DecimalMax(value = "1.0", message = "温度不能大于 1")
    private BigDecimal temperature;

    private BigDecimal scoreThreshold;
    private Integer enabled;
    private Integer knowledgeProfileEnabled;
}
