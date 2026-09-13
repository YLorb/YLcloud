package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空间成员展示对象。
 */
@Data
public class SpaceMemberVO {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String role;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
