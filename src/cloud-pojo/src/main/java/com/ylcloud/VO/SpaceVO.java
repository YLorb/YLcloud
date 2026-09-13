package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间展示对象。
 */
@Data
public class SpaceVO {
    private Long id;
    private String name;
    private String description;
    private String type;
    private String lifecycleState;
    private Long ownerId;
    private Long rootDirId;
    private String role;
    private Integer ragStatus;
    private Integer versionEnabled;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
