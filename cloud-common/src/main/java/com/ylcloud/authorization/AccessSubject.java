package com.ylcloud.authorization;

import java.util.Objects;

/**
 * A normalized authenticated principal.
 *
 * <p>API keys and system tasks always retain the user whose current resource
 * permissions bound their execution. Their own scope/budget checks are applied
 * in addition to, never instead of, these resource checks.</p>
 */
public record AccessSubject(
        AccessSubjectType type,
        Long userId,
        String referenceId
) {
    public AccessSubject {
        Objects.requireNonNull(type, "subject type is required");
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("subject userId must be positive");
        }
        if (type != AccessSubjectType.USER && (referenceId == null || referenceId.isBlank())) {
            throw new IllegalArgumentException("non-user subjects require a reference id");
        }
    }

    public static AccessSubject user(Long userId) {
        return new AccessSubject(AccessSubjectType.USER, userId, null);
    }

    public static AccessSubject apiKey(Long userId, Long apiKeyId) {
        if (apiKeyId == null || apiKeyId < 1) {
            throw new IllegalArgumentException("apiKeyId must be positive");
        }
        return new AccessSubject(AccessSubjectType.API_KEY, userId, String.valueOf(apiKeyId));
    }

    public static AccessSubject systemTask(Long userId, String taskId) {
        return new AccessSubject(AccessSubjectType.SYSTEM_TASK, userId, taskId);
    }
}
