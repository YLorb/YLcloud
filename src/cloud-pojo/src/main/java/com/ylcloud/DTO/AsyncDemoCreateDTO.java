package com.ylcloud.DTO;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AsyncDemoCreateDTO {
    @NotBlank @Size(max = 200)
    private String text;
    @Min(0) @Max(180000)
    private Integer delayMs = 0;
    @Pattern(regexp = "^(SUCCESS|RETRYABLE|FATAL|TIMEOUT)$")
    private String mode = "SUCCESS";
    @Size(max = 120)
    private String idempotencyKey;
}
