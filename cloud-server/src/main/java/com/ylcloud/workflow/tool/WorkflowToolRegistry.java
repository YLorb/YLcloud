package com.ylcloud.workflow.tool;

import com.ylcloud.Exception.BaseException;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** deny-by-default Tool Registry；启动时拒绝重名，运行时拒绝一切未注册名称。 */
@Component
public class WorkflowToolRegistry {
    private final Map<String, WorkflowToolHandler> handlers;

    public WorkflowToolRegistry(List<WorkflowToolHandler> values) {
        Map<String, WorkflowToolHandler> registered = new LinkedHashMap<>();
        for (WorkflowToolHandler handler : values) {
            if (handler == null || handler.name() == null || !handler.name().matches("^[a-z][a-z0-9_.-]{1,127}$"))
                throw new IllegalStateException("invalid workflow tool registration");
            if (registered.putIfAbsent(handler.name(), handler) != null)
                throw new IllegalStateException("duplicate workflow tool: " + handler.name());
        }
        handlers = Map.copyOf(registered);
    }

    public WorkflowToolHandler require(String name) {
        WorkflowToolHandler handler = handlers.get(name);
        if (handler == null) throw new BaseException(404, "Tool 未注册");
        return handler;
    }

    public List<String> names() { return handlers.keySet().stream().sorted().toList(); }
}
