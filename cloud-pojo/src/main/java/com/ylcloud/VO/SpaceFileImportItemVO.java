package com.ylcloud.VO;

import lombok.Data;

@Data
public class SpaceFileImportItemVO {
    private Long id;
    private Long sourceUserFileId;
    private String relativePath;
    private String itemStatus;
    private String errorCode;
    private String errorMessage;
    private Long spaceFileId;
    private String sandboxInvocationId;
}
