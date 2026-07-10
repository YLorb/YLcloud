package com.ylcloud.VO;

import lombok.Data;

@Data
public class StorageQuotaVO {
    private Long usedBytes;
    private Long totalBytes;
    private Long availableBytes;
    private Double usagePercent;
    private Integer fileCount;
    private String policyName;
}
