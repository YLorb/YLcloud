package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    @ToString.Exclude
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String nickname;
}
