package com.ylcloud.async.worker;

import com.ylcloud.entity.UnifiedAsyncTask;

public interface TaskHandler {
    String taskType();
    Object execute(UnifiedAsyncTask task, TaskExecutionContext context) throws Exception;
}
