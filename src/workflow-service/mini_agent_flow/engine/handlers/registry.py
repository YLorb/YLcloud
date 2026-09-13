from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.handlers.base import NodeHandler
from mini_agent_flow.engine.handlers.control import ControlNodeHandler
from mini_agent_flow.engine.handlers.llm import LLMNodeHandler
from mini_agent_flow.engine.handlers.merge import MergeNodeHandler
from mini_agent_flow.engine.handlers.tool import ToolNodeHandler


class NodeHandlerRegistry:
    def __init__(self) -> None:
        self._handlers: dict[str, NodeHandler] = {
            "start": ControlNodeHandler(),
            "end": ControlNodeHandler(),
            "llm": LLMNodeHandler(),
            "tool": ToolNodeHandler(),
            "merge": MergeNodeHandler(),
        }

    def register(self, node_type: str, handler: NodeHandler) -> None:
        if not node_type or node_type in self._handlers:
            raise ValueError(f"node handler is already registered: {node_type}")
        self._handlers[node_type] = handler

    def get(self, node_type: str) -> NodeHandler:
        try:
            return self._handlers[node_type]
        except KeyError as exc:
            raise WorkflowExecutionError(
                f"unsupported graph node type: {node_type}"
            ) from exc
