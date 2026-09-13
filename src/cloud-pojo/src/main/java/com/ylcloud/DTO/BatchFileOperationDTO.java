package com.ylcloud.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BatchFileOperationDTO {
    @NotEmpty
    @Size(max = 100)
    private List<Long> fileIds;

    private Long targetParentId;
}
