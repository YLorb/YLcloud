package com.ylcloud.VO;

import lombok.Data;

@Data
public class UserApiKeyCreatedVO {
    private UserApiKeyVO apiKey;
    private String plaintext;
}
