package com.ylcloud.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

@Data
public class AdminResourceGrantCreateDTO {
    @NotNull
    private Long adminUserId;
    @NotNull
    private String resourceType;
    @NotNull
    private Long resourceId;
    @NotEmpty
    private Set<String> actions;
}
