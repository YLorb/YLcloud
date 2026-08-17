package com.ylcloud.VO;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SpaceFileCapabilityVO {
    boolean canRead;
    boolean canCreate;
    boolean canRename;
    boolean canMove;
    boolean canRemove;
    boolean canUploadVersion;
    boolean canManageVersions;
}
