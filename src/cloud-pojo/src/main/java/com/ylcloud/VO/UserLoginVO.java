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

    private String role;

    private Boolean deploymentOwner;

}
