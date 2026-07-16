package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeChatQueryCreateDTO {
    @NotBlank
    @Size(max = 2000)
    private String question;

    @NotEmpty
    @Size(max = 5)
    private List<Long> spaceIds;

    private String retrievalMode;
    @Size(max = 10)
    private List<RagChatMessageDTO> history;
}
