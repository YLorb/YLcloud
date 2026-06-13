package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 添加空间成员请求参数。
 */
@Data
public class SpaceMemberAddDTO {
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    private String role;
}
