package com.ylcloud.async.task;

public record TaskCreateCommand(
        String taskKey,
        String taskDomain,
        String taskType,
        Object payload,
        Long createdBy,
        Long spaceId,
        String resourceKey,
        long resourceVersion
) {}
