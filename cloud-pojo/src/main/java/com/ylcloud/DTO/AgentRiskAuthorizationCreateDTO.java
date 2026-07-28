package com.ylcloud.DTO;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRiskAuthorizationCreateDTO {
    @NotBlank
    @Pattern(regexp = "ALLOW_ONCE|PERSISTENT")
    private String mode;

    private LocalDateTime expiresAt;

    @AssertTrue(message = "必须明确接受高风险 Agent 授权风险")
    private boolean riskAcknowledged;
}
