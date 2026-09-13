package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SpaceMemberRoleDTO {
    @NotBlank(message = "成员角色不能为空")
    @Pattern(regexp = "^(ADMIN|EDITOR|VIEWER|MEMBER)$", message = "成员角色不合法")
    private String role;
}
