package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改空间成员角色请求参数。
 */
@Data
public class SpaceMemberRoleDTO {
    @NotBlank(message = "成员角色不能为空")
    private String role;
}
