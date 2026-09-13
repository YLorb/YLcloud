package com.ylcloud.DTO;

import lombok.Data;

import java.util.Map;

@Data
public class AdminUserAccessUpdateDTO {
    private Long groupId;
    private boolean clearGroup;
    private Map<String, Boolean> overrides;
}
