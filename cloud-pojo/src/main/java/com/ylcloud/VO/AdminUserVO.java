package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String role;
    private Integer status;
    private Long groupId;
    private String groupName;
    private Map<String, Boolean> permissionOverrides = new LinkedHashMap<>();
    private Map<String, Boolean> effectivePermissions = new LinkedHashMap<>();
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
