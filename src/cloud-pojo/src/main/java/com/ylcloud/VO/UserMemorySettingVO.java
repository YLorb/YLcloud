package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class UserMemorySettingVO {
    private Boolean enabled;
    private Integer retentionDays;
}
