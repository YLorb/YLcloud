package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserLoginVO {
    private Long id;

    private String username;

    private String nickname;

    // TODO：暂时使用 token 鉴权，后期使用 session 鉴权，避免频繁登录
    private String token;
}
