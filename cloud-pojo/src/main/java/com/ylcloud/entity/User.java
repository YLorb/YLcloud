package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;

    private String username;

    private String password;

    private String nickname;

    private Long rootID;

    private String email;

    private String avatar;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}