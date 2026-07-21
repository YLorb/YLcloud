package com.ylcloud.DTO;

import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class WorkflowConfirmationCreateDTO {
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{1,127}$")
    private String toolName;
    @NotNull
    private ConfirmationMode mode;
    @NotNull @Size(max = 64)
    private Map<String, Object> arguments;
    @Size(max = 32)
    private Map<String, Object> similarityScope;
    @Min(30) @Max(600)
    private Integer ttlSeconds = 300;
}
