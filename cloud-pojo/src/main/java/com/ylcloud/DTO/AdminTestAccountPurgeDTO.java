package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * E2E 测试账号立即进入安全删除编排的管理员确认参数。
 */
@Data
public class AdminTestAccountPurgeDTO {
    @NotBlank(message = "预期用户名不能为空")
    @Pattern(regexp = "^e2e_course_[A-Za-z0-9_]+$", message = "只允许清理课程 E2E 测试账号")
    private String expectedUsername;
}
