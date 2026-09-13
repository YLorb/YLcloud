package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class WebhookSubscriptionCreateDTO {
    @NotBlank
    @Size(max = 100)
    private String name;
    @NotBlank
    @Size(max = 2048)
    private String targetUrl;
    @NotNull
    private Long apiKeyId;
    @NotEmpty
    @Size(max = 32)
    private Set<String> eventTypes = new LinkedHashSet<>();
    private boolean includeContent;
}
