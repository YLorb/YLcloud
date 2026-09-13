package com.ylcloud.VO;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserMemoryVO {
    private Long id;
    private Long sourceSessionId;
    private Long sourceMessageId;
    private String memoryType;
    private String content;
    private String normalizedKey;
    private BigDecimal confidence;
    private Boolean userConfirmed;
    private Boolean pinned;
    private LocalDateTime expiresAt;
    private Integer version;
    private String memoryStatus;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
