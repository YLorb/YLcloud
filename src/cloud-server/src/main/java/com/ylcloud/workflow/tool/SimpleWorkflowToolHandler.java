package com.ylcloud.workflow.tool;

import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import java.util.Map;
import java.util.function.BiFunction;

/** 将显式 Java 函数注册为 Tool；不支持类名、方法名或反射参数。 */
public final class SimpleWorkflowToolHandler implements WorkflowToolHandler {
    private final String name;
    private final RiskLevel riskLevel;
    private final String scope;
    private final BiFunction<ToolInvocationContext, Map<String, Object>, Map<String, Object>> action;

    public SimpleWorkflowToolHandler(String name, RiskLevel riskLevel, String scope,
                                     BiFunction<ToolInvocationContext, Map<String, Object>, Map<String, Object>> action) {
        this.name = name; this.riskLevel = riskLevel; this.scope = scope; this.action = action;
    }
    public String name() { return name; }
    public RiskLevel riskLevel() { return riskLevel; }
    public String requiredScope() { return scope; }
    public Map<String, Object> invoke(ToolInvocationContext context, Map<String, Object> arguments) {
        return action.apply(context, arguments);
    }
}
