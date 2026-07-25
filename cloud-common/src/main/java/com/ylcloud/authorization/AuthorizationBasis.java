package com.ylcloud.authorization;

/** Why access was granted; useful for audit hooks and security tests. */
public enum AuthorizationBasis {
    SELF,
    SPACE_MEMBER,
    ADMIN_RESOURCE_GRANT,
    DEPLOYMENT_OWNER
}
