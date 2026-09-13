package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PermissionDefinitionVO {
    private String key;
    private String label;
    private String description;
}
