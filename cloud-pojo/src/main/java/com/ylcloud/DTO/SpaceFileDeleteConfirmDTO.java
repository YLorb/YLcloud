package com.ylcloud.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpaceFileDeleteConfirmDTO {
    @NotBlank(message = "确认名称不能为空")
    private String confirmationName;
    @NotBlank(message = "确认令牌不能为空")
    private String confirmationToken;
    @NotNull(message = "节点版本不能为空")
    private Long expectedVersion;
    @NotNull(message = "确认令牌有效期不能为空")
    private Long expiresAtEpochSecond;
}
