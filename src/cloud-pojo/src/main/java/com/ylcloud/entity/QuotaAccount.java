package com.ylcloud.entity;

import lombok.Data;

@Data
public class QuotaAccount {
    private Long id;
    private String accountType;
    private Long referenceId;
    private Long groupId;
}
