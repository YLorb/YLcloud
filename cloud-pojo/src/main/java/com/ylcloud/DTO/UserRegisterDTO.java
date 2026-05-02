package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserRegisterDTO {

    @NotBlank(message = "username must not be blank")
    private String username;

    @NotBlank(message = "password must not be blank")
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String nickname;
}
