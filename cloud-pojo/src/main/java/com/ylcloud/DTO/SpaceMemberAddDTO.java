package com.ylcloud.DTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SpaceMemberAddDTO {
    @NotNull(message = "用户 ID 不能为空")
    @Min(value = 1, message = "用户 ID 必须大于 0")
    private Long userId;

    @Pattern(regexp = "^(OWNER|ADMIN|EDITOR|VIEWER|MEMBER)?$", message = "成员角色不合法")
    private String role;
}
