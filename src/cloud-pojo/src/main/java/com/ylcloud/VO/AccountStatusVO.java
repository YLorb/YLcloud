package com.ylcloud.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AccountStatusVO {
    private Long userId;
    private String username;
    private String accountStatus;
    private LocalDateTime cancelledAt;
    private LocalDateTime recoverableUntil;
    private LocalDateTime purgingStartedAt;
    private LocalDateTime purgedAt;
    private Boolean canRecover;
    private Boolean isTeamOwner;
    private Integer ownedTeamCount;
}
