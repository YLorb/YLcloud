package com.ylcloud.entity;

import lombok.Data;

@Data
public class QuotaBlobReference {
    private String referenceType;
    private Long referenceId;
    private Long accountId;
    private String contentKey;
    private String fileUuid;
    private Long sizeBytes;
    private Boolean active;
}
