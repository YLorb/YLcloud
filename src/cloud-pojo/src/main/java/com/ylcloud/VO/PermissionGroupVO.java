package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class PermissionGroupVO {
    private Long id;
    private String name;
    private String description;
    private boolean systemGroup;
    private int userCount;
    private Map<String, Boolean> permissions = new LinkedHashMap<>();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
