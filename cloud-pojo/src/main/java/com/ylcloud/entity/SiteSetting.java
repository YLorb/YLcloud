package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SiteSetting {
    private Long id;
    private String settingKey;
    private String settingValue;
    private String valueType;
    private String groupName;
    private String label;
    private String description;
    private Integer secret;
    private Integer editable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
