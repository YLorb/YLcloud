package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OpenApiFolderCreateDTO {
    @NotNull
    private Long parentId;
    @NotBlank
    @Size(max = 255)
    private String name;
}
