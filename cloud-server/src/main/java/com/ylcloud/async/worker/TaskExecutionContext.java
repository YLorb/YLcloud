package com.ylcloud.async.worker;

import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.UnifiedAsyncTask;

public class TaskExecutionContext {
    private final UnifiedTaskCenterService taskCenter;
    private final UnifiedAsyncTask task;
    private final String leaseToken;

    public TaskExecutionContext(UnifiedTaskCenterService taskCenter, UnifiedAsyncTask task, String leaseToken) {
        this.taskCenter = taskCenter;
        this.task = task;
        this.leaseToken = leaseToken;
    }

    public void checkpoint() {
        if(taskCenter.checkpointCancel(task,leaseToken)) throw new TaskCanceledException();
        if(!taskCenter.heartbeat(task,leaseToken)) throw new StaleWorkerException();
    }
}
