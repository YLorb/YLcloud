from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.models import EndNode, LLMNode, StartNode, ToolNode, Workflow, WorkflowNode
from mini_agent_flow.engine.resolver import VariableResolver
from mini_agent_flow.llm.base import LLMClient
from mini_agent_flow.tools.registry import ToolRegistry


class WorkflowExecutionError(RuntimeError):
    """workflow 执行失败时抛出的异常。

    结构校验由 Validator 负责；Executor 仍保留防御性检查，用于捕获运行时错误、
    未支持节点类型、工具/LLM 调用失败和意外循环。
    """


@dataclass(frozen=True)
class WorkflowRunResult:
    """workflow 执行结果快照。"""

    workflow_name: str
    context: dict[str, Any]
    final_output: Any | None
    executed_nodes: list[str]


class SequentialWorkflowExecutor:
    """Level 1 顺序执行器。

    执行器从 start 节点开始，按每个节点的 next 字段顺序执行，直到遇到 end。
    当前只支持 start / llm / tool / end，不处理 condition、loop、retry 或并发。
    """

    def __init__(
        self,
        llm: LLMClient,
        tool_registry: ToolRegistry,
        resolver: VariableResolver | None = None,
        max_steps: int = 100,
    ) -> None:
        """创建顺序执行器。"""

        if max_steps <= 0:
            raise WorkflowExecutionError("max_steps must be greater than 0")

        self.llm = llm
        self.tool_registry = tool_registry
        self.resolver = resolver or VariableResolver()
        self.max_steps = max_steps

    def run(self, workflow: Workflow) -> WorkflowRunResult:
        """执行 workflow，并返回最终 Context 快照。"""

        context = WorkflowContext.from_workflow(workflow)
        node_by_id = self._index_nodes(workflow)
        current_node_id = self._find_start_node(workflow).id
        executed_nodes: list[str] = []

        for _ in range(self.max_steps):
            node = self._get_node(node_by_id, current_node_id)
            executed_nodes.append(node.id)

            if isinstance(node, StartNode):
                current_node_id = node.next
                continue

            if isinstance(node, LLMNode):
                current_node_id = self._execute_llm_node(node, context)
                continue

            if isinstance(node, ToolNode):
                current_node_id = self._execute_tool_node(node, context)
                continue

            if isinstance(node, EndNode):
                return WorkflowRunResult(
                    workflow_name=workflow.name,
                    context=context.to_dict(),
                    final_output=context.get("final_answer", None),
                    executed_nodes=executed_nodes,
                )

            raise WorkflowExecutionError(f"unsupported node type: {node.type}")

        raise WorkflowExecutionError(f"workflow exceeded max steps: {self.max_steps}")

    def _execute_llm_node(self, node: LLMNode, context: WorkflowContext) -> str:
        """执行 LLM 节点：渲染 prompt、调用 llm、写入 output。"""

        try:
            prompt = self.resolver.resolve_template(node.prompt, context)
            result = self.llm.generate(prompt)
            context.set(node.output, result)
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute llm node: {node.id}") from exc

        return node.next

    def _execute_tool_node(self, node: ToolNode, context: WorkflowContext) -> str:
        """执行 Tool 节点：解析 input、获取工具、调用工具、写入 output。"""

        try:
            tool_input = self.resolver.resolve_value(node.input, context)
            tool = self.tool_registry.get(node.tool)
            result = tool(tool_input)
            context.set(node.output, result)
        except Exception as exc:
            raise WorkflowExecutionError(f"failed to execute tool node: {node.id}") from exc

        return node.next

    def _index_nodes(self, workflow: Workflow) -> dict[str, WorkflowNode]:
        """建立 node_id 到节点对象的映射。"""

        return {node.id: node for node in workflow.nodes}

    def _find_start_node(self, workflow: Workflow) -> StartNode:
        """获取 start 节点。"""

        for node in workflow.nodes:
            if isinstance(node, StartNode):
                return node
        raise WorkflowExecutionError("workflow start node is missing")

    def _get_node(self, node_by_id: dict[str, WorkflowNode], node_id: str) -> WorkflowNode:
        """按 id 获取节点；找不到时抛运行时错误。"""

        try:
            return node_by_id[node_id]
        except KeyError as exc:
            raise WorkflowExecutionError(f"workflow points to unknown node: {node_id}") from exc

