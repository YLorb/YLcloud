from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.engine.models import (
    ConditionNode,
    EndNode,
    LLMNode,
    LoopNode,
    StartNode,
    ToolNode,
    Workflow,
)


class GraphCompileError(ValueError):
    """Workflow 无法编译为 v2 Graph IR。"""


@dataclass(frozen=True)
class V1ToV2Compiler:
    """把已支持的 Workflow v1 确定性转换成 v2 显式边结构。"""

    def compile(self, workflow: Workflow) -> GraphWorkflow:
        conditions = {
            node.id: node for node in workflow.nodes if isinstance(node, ConditionNode)
        }
        nodes: list[dict[str, Any]] = []
        edges: list[dict[str, Any]] = []

        for node in workflow.nodes:
            if isinstance(node, ConditionNode):
                continue
            nodes.append(self._convert_node(node))

        for node in workflow.nodes:
            if isinstance(node, ConditionNode | EndNode):
                continue
            target = self._next_target(node)
            self._append_expanded_edges(
                edges=edges,
                source=node.id,
                target=target,
                conditions=conditions,
                visited=set(),
            )

        data = {
            "version": "2.0",
            "name": workflow.name,
            "description": workflow.description,
            "inputs": workflow.inputs,
            "outputs": workflow.outputs,
            "metadata": {**workflow.metadata, "compiled_from_version": "1.0"},
            "nodes": nodes,
            "edges": edges,
        }
        return GraphWorkflow.model_validate(data)

    def _convert_node(self, node: Any) -> dict[str, Any]:
        common = {
            "id": node.id,
            "type": node.type,
            "description": node.description,
            "metadata": node.metadata,
            "retry": node.retry.model_dump() if node.retry else None,
            "timeout_seconds": node.timeout_seconds,
        }
        common = {key: value for key, value in common.items() if value is not None}

        if isinstance(node, StartNode | EndNode):
            return common
        if isinstance(node, LLMNode):
            return {**common, "prompt": node.prompt, "output": node.output, "publish": True}
        if isinstance(node, ToolNode):
            return {
                **common,
                "tool": node.tool,
                "input": node.input,
                "output": node.output,
                "publish": True,
            }
        if isinstance(node, LoopNode):
            raise GraphCompileError(
                "Workflow v1 loop has no stable execution semantics; express it as a v2 loop"
            )
        raise GraphCompileError(f"unsupported v1 node type: {node.type}")

    def _next_target(self, node: Any) -> str:
        if isinstance(node, StartNode | LLMNode | ToolNode):
            return node.next
        raise GraphCompileError(f"v1 node does not have a deterministic next target: {node.id}")

    def _append_expanded_edges(
        self,
        *,
        edges: list[dict[str, Any]],
        source: str,
        target: str,
        conditions: dict[str, ConditionNode],
        visited: set[str],
    ) -> None:
        condition = conditions.get(target)
        if condition is None:
            edges.append({"from": source, "to": target})
            return
        if condition.id in visited:
            raise GraphCompileError(f"v1 condition chain contains a cycle: {condition.id}")

        next_visited = {*visited, condition.id}
        true_target = conditions.get(condition.if_true)
        false_target = conditions.get(condition.if_false)
        if true_target is not None or false_target is not None:
            raise GraphCompileError("chained v1 condition nodes are not supported by the compatibility compiler")

        metadata = {"compiled_from_condition": condition.id}
        if condition.if_true == condition.if_false:
            edges.append(
                {
                    "from": source,
                    "to": condition.if_true,
                    "metadata": metadata,
                }
            )
            return
        edges.append(
            {
                "from": source,
                "to": condition.if_true,
                "condition": {
                    "source": condition.expression,
                    "operator": "truthy",
                },
                "metadata": metadata,
            }
        )
        edges.append(
            {
                "from": source,
                "to": condition.if_false,
                "default": True,
                "metadata": metadata,
            }
        )
