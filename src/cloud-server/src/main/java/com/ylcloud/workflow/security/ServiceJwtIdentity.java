package com.ylcloud.workflow.security;

import java.util.Set;

/** 已验签、验 audience、验时效和 scope 后的服务身份。 */
public record ServiceJwtIdentity(
        String issuer,
        String subject,
        String audience,
        Set<String> scopes,
        ServiceJwtBinding binding,
        String tokenId
) {
}
