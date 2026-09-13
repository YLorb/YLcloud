package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class AdminPermissionGroupDTO {
    @NotBlank(message = "用户组名称不能为空")
    @Size(max = 100, message = "用户组名称不能超过100个字符")
    private String name;

    @Size(max = 500, message = "用户组说明不能超过500个字符")
    private String description;

    private Map<String, Boolean> permissions;
}
