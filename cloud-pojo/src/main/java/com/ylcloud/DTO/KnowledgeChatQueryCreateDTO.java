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

    /**
     * 服务端写入的调用主体绑定；所有外部 Controller 必须覆盖客户端输入。
     */
    private Long apiKeyId;
}
