package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间成员实体，对应 space_member 表。
 */
@Data
public class SpaceMember {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String role;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
