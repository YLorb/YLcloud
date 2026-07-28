package com.ylcloud.workflow.tool;

import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import java.util.Map;

/** 显式注册的受控 Tool；禁止通过反射暴露任意 Controller、Service 或 Mapper。 */
public interface WorkflowToolHandler {
    String name();
    RiskLevel riskLevel();
    String requiredScope();
    Map<String, Object> invoke(ToolInvocationContext context, Map<String, Object> arguments);
}
