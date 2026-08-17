package com.ylcloud.VO;

import lombok.Data;
import java.util.List;

@Data
public class SpaceFileImportBatchVO {
    private Long id;
    private Long spaceId;
    private Long targetParentId;
    private String failurePolicy;
    private String batchStatus;
    private Integer totalCount;
    private Integer passedCount;
    private Integer failedCount;
    private Integer importedCount;
    private String errorMessage;
    private Long asyncTaskId;
    private List<SpaceFileImportItemVO> items;
}
