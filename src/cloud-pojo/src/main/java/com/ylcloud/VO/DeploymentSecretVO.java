package com.ylcloud.VO;

import java.time.LocalDateTime;

public record DeploymentSecretVO(
        String key,
        String label,
        String description,
        boolean configured,
        boolean editable,
        String activation,
        LocalDateTime updateTime
) {
}
