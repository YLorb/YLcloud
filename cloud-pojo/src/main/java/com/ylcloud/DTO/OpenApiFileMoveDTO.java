package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OpenApiFileMoveDTO {
    @NotNull
    private Long targetParentId;
}
