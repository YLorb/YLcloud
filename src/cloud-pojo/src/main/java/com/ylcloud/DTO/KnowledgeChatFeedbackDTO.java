package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeChatFeedbackDTO {
    @NotBlank @Pattern(regexp = "HELPFUL|UNHELPFUL")
    private String rating;
    @Size(max = 64)
    private String reason;
    @Size(max = 500)
    private String comment;
}
