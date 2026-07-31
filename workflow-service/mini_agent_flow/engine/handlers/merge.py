from typing import Any

from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_models import GraphMergeNode, GraphNode
from mini_agent_flow.engine.handlers.base import NodeExecutionResult, NodeRuntimeContext


class MergeNodeHandler:
    def execute(self, node: GraphNode, runtime: NodeRuntimeContext) -> NodeExecutionResult:
        if not isinstance(node, GraphMergeNode):
            raise WorkflowExecutionError(f"merge handler cannot execute node: {node.id}")
        values: dict[str, Any] = {}
        node_outputs = runtime.context.require("node_outputs")
        for alias, reference in node.inputs.items():
            try:
                values[alias] = node_outputs[reference.node][reference.output]
            except (KeyError, TypeError):
                if not reference.required or "default" in reference.model_fields_set:
                    values[alias] = reference.default
                else:
                    raise WorkflowExecutionError(
                        f"node output is unavailable: {reference.node}.{reference.output}"
                    ) from None
        if node.strategy == "object":
            result: Any = values
        elif node.strategy == "list":
            result = list(values.values())
        else:
            result = next(iter(values.values()))
        return NodeExecutionResult(
            input_data=values,
            outputs={node.output: result},
            publish_patch={node.output: result} if node.publish else {},
        )
