package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserLoginDTO {

    @NotBlank(message = "用户名不能为空")
    @jakarta.validation.constraints.Size(max=100)
    private String username;

    @NotBlank(message = "密码不能为空")
    @jakarta.validation.constraints.Size(max=128)
    private String password;

    private boolean rememberMe;
}
