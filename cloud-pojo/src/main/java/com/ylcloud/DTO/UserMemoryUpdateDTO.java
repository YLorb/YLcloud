package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserMemoryUpdateDTO {
    @NotBlank @Size(max = 2000)
    private String content;
    @NotBlank @Pattern(regexp = "FACT|PREFERENCE|CONSTRAINT|DECISION")
    private String memoryType;
}
