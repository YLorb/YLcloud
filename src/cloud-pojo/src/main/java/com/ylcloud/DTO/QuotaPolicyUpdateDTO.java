package com.ylcloud.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class QuotaPolicyUpdateDTO {
    @NotNull @PositiveOrZero private Long storageBytes;
    @NotNull @PositiveOrZero private Long maxFileBytes;
    @NotNull @PositiveOrZero private Long spaceLimit;
    @NotNull @PositiveOrZero private Long monthlyApiCalls;
    @NotNull @PositiveOrZero private Long monthlyModelTokens;
    @NotNull @PositiveOrZero private Long monthlyAgentTasks;
    @NotNull @PositiveOrZero private Long concurrentAgentTasks;
}
