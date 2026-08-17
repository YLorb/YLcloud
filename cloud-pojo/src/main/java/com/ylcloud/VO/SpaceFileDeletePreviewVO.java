package com.ylcloud.VO;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SpaceFileDeletePreviewVO {
    Long fileId;
    String name;
    Long nodeVersion;
    Integer folderCount;
    Integer fileCount;
    Integer knowledgeCount;
    String subtreeDigest;
    String confirmationToken;
    Long expiresAtEpochSecond;
}
