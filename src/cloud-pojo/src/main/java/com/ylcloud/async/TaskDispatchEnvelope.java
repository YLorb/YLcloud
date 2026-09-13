package com.ylcloud.async;

import java.time.Instant;

public record TaskDispatchEnvelope(
        int schemaVersion,
        String messageId,
        String eventType,
        Long taskId,
        String taskDomain,
        String taskType,
        Integer expectedAttemptVersion,
        String resourceKey,
        Long resourceVersion,
        Instant createdAt,
        String traceId
) {
}
