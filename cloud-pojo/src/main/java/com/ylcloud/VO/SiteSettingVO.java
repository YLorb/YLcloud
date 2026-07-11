package com.ylcloud.VO;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SiteSettingVO {
    private String key;
    private String value;
    private String maskedValue;
    private String valueType;
    private String groupName;
    private String label;
    private String description;
    private Boolean secret;
    private Boolean editable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
