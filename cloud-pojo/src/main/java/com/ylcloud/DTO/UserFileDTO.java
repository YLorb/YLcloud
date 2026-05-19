package com.ylcloud.DTO;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserFileDTO {
    private Long id;

    private String fileName;

    private String fileUuid;

    private int Dir;

    private int status;

    private Long userId;

    private Long parentId;

    private String path;

    private LocalDateTime createtime;

    private LocalDateTime updatetime;
}
