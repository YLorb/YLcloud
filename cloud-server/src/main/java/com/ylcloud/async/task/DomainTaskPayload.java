package com.ylcloud.async.task;

import java.util.Map;

public record DomainTaskPayload(Long resourceId, Long spaceId, Map<String, Object> metadata) {
    public DomainTaskPayload(Long resourceId) {
        this(resourceId, null, null);
    }
}
