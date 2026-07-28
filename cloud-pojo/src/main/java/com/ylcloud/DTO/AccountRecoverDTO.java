package com.ylcloud.DTO;

import lombok.Data;

@Data
public class AccountRecoverDTO {
    private Long userId;
    private String reason;
}
