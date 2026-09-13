package com.ylcloud.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SpaceFileImportBatch {
    private Long id; private String batchKey; private Long spaceId; private Long targetParentId;
    private String failurePolicy; private String batchStatus; private Integer totalCount; private Integer passedCount;
    private Integer failedCount; private Integer importedCount; private String errorMessage; private Long asyncTaskId;
    private Long createdBy; private Long resourceVersion; private LocalDateTime createtime; private LocalDateTime updatetime;
}
