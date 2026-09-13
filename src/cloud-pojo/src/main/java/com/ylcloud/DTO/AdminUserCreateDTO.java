package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class AdminUserCreateDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "用户名不能超过100个字符")
    private String username;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 100, message = "初始密码长度必须为8到100个字符")
    @ToString.Exclude
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 100, message = "昵称不能超过100个字符")
    private String nickname;

    @Size(max = 255, message = "邮箱不能超过255个字符")
    private String email;

    private String role;
    private Long groupId;
}
