package com.ylcloud.async.worker;

import com.ylcloud.async.task.FatalTaskException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TaskHandlerRegistry {
    private final Map<String,TaskHandler> handlers;

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(TaskHandler::taskType, Function.identity()));
    }

    public TaskHandler require(String taskType) {
        TaskHandler handler = handlers.get(taskType);
        if(handler == null) throw new FatalTaskException("不支持的任务类型: " + taskType);
        return handler;
    }
}
