package com.ylcloud.async.task;

public record TaskCreateCommand(
        String taskKey,
        String taskDomain,
        String taskType,
        Object payload,
        Long createdBy,
        Long spaceId,
        String resourceKey,
        long resourceVersion,
        Long parentTaskId,
        Integer maxAttempts
) {
    public TaskCreateCommand(String taskKey,String taskDomain,String taskType,Object payload,Long createdBy,
                             Long spaceId,String resourceKey,long resourceVersion) {
        this(taskKey,taskDomain,taskType,payload,createdBy,spaceId,resourceKey,resourceVersion,null,null);
    }

    public TaskCreateCommand(String taskKey,String taskDomain,String taskType,Object payload,Long createdBy,
                             Long spaceId,String resourceKey,long resourceVersion,Long parentTaskId) {
        this(taskKey,taskDomain,taskType,payload,createdBy,spaceId,resourceKey,resourceVersion,parentTaskId,null);
    }
}
