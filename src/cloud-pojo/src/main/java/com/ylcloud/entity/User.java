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

    private String role;

    private Boolean deploymentOwner;

    // Account lifecycle fields (TASK-010)
    private String accountStatus;
    private LocalDateTime cancelledAt;
    private Long cancelRequestedBy;
    private LocalDateTime recoverableUntil;
    private LocalDateTime purgingStartedAt;
    private LocalDateTime purgedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
