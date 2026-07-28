package com.ylcloud.DTO;

import lombok.Data;

@Data
public class AccountCancelDTO {
    private String reason;
    private Boolean confirmTeamOwnerTransfer;
}
