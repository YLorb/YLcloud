from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_models import GraphEndNode, GraphNode, GraphStartNode
from mini_agent_flow.engine.handlers.base import NodeExecutionResult, NodeRuntimeContext


class ControlNodeHandler:
    def execute(self, node: GraphNode, runtime: NodeRuntimeContext) -> NodeExecutionResult:
        if not isinstance(node, (GraphStartNode, GraphEndNode)):
            raise WorkflowExecutionError(f"control handler cannot execute node: {node.id}")
        return NodeExecutionResult()
