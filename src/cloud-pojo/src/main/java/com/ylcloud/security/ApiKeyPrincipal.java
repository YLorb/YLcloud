package com.ylcloud.security;

import java.util.Set;

public record ApiKeyPrincipal(Long keyId,Long userId,String prefix,String driveAccess,Long driveRootFileId,
                              Set<String> scopes,Set<Long> spaceIds) {
}
