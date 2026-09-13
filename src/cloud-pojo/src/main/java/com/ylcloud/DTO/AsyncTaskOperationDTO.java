package com.ylcloud.DTO;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AsyncTaskOperationDTO {
    @Size(max = 500)
    private String reason;
}
