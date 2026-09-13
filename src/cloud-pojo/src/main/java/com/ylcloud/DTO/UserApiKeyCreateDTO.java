package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class UserApiKeyCreateDTO {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Pattern(regexp = "NONE|READ|WRITE")
    private String driveAccess;

    private Long driveRootFileId;
    private boolean knowledgeRetrieve;
    private boolean knowledgeAgent;
    private boolean selectAllVisibleSpaces;
    @Size(max = 500)
    private Set<Long> spaceIds = new LinkedHashSet<>();
    private LocalDateTime expiresAt;
    private boolean neverExpires;
    private boolean allowHighRisk;
    private boolean riskAcknowledged;
}
