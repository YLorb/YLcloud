package com.ylcloud.VO;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SpaceFileDeleteTaskVO {
    Long batchId;
    Long asyncTaskId;
    String status;
    Boolean cancellable;
}
