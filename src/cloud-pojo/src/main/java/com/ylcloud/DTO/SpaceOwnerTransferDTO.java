package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpaceOwnerTransferDTO {
    @NotNull
    private Long targetUserId;
}
