package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SpaceFileImportItem {
    private Long id; private Long batchId; private Long sourceUserFileId; private String sourceType;
    private String relativePath; private String fileUuid; private String contentHash; private Long fileSize;
    private String itemStatus; private String errorCode; private String errorMessage; private Long spaceFileId;
    private String sandboxInvocationId; private LocalDateTime createtime; private LocalDateTime updatetime;
}
