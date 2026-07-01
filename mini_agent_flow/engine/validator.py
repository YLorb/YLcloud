from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from mini_agent_flow.engine.models import (
    ConditionNode,
    LLMNode,
    LoopNode,
    StartNode,
    ToolNode,
    Workflow,
    WorkflowNode,
)


class WorkflowValidationError(ValueError):
    """Raised when a workflow is structurally or semantically invalid."""


class WorkflowValidator:
    def __init__(self, allowed_tools: set[str] | None = None) -> None:
        self.allowed_tools = allowed_tools

    def validate_file(self, path: str | Path) -> Workflow:
        workflow_path = Path(path)
        try:
            data = json.loads(workflow_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise WorkflowValidationError(f"invalid JSON workflow: {exc}") from exc
        except OSError as exc:
            raise WorkflowValidationError(f"cannot read workflow file: {workflow_path}") from exc

        return self.validate_data(data)

    def validate_data(self, data: dict[str, Any]) -> Workflow:
        try:
            workflow = Workflow.model_validate(data)
        except ValidationError as exc:
            raise WorkflowValidationError(str(exc)) from exc

        self._validate_edges(workflow)
        self._validate_allowed_tools(workflow)
        self._validate_reachability(workflow)
        self._validate_path_to_end(workflow)
        return workflow

    def _validate_edges(self, workflow: Workflow) -> None:
        node_ids = {node.id for node in workflow.nodes}
        missing_edges: list[str] = []

        for node in workflow.nodes:
            for target in self._outgoing_targets(node):
                if target not in node_ids:
                    missing_edges.append(f"{node.id} -> {target}")

        if missing_edges:
            joined_edges = ", ".join(missing_edges)
            raise WorkflowValidationError(f"workflow has edges pointing to unknown nodes: {joined_edges}")

    def _validate_allowed_tools(self, workflow: Workflow) -> None:
        if self.allowed_tools is None:
            return

        invalid_tools = [
            node.tool
            for node in workflow.nodes
            if isinstance(node, ToolNode) and node.tool not in self.allowed_tools
        ]
        if invalid_tools:
            joined_tools = ", ".join(sorted(set(invalid_tools)))
            raise WorkflowValidationError(f"workflow references unsupported tools: {joined_tools}")

    def _validate_reachability(self, workflow: Workflow) -> None:
        graph = self._build_graph(workflow)
        start_id = self._start_node(workflow).id
        reachable = self._reachable_nodes(graph, start_id)
        all_ids = {node.id for node in workflow.nodes}
        unreachable = sorted(all_ids - reachable)

        if unreachable:
            raise WorkflowValidationError(f"workflow contains unreachable nodes: {', '.join(unreachable)}")

    def _validate_path_to_end(self, workflow: Workflow) -> None:
        graph = self._build_graph(workflow)
        start_id = self._start_node(workflow).id
        end_ids = {node.id for node in workflow.nodes if node.type == "end"}
        reachable = self._reachable_nodes(graph, start_id)

        if not end_ids.intersection(reachable):
            raise WorkflowValidationError("workflow must contain at least one path from start to end")

    def _build_graph(self, workflow: Workflow) -> dict[str, list[str]]:
        return {node.id: self._outgoing_targets(node) for node in workflow.nodes}

    def _outgoing_targets(self, node: WorkflowNode) -> list[str]:
        if isinstance(node, (StartNode, LLMNode, ToolNode)):
            return [node.next]
        if isinstance(node, ConditionNode):
            return [node.if_true, node.if_false]
        if isinstance(node, LoopNode):
            return [*node.body, node.next]
        return []

    def _reachable_nodes(self, graph: dict[str, list[str]], start_id: str) -> set[str]:
        visited: set[str] = set()
        stack = [start_id]

        while stack:
            node_id = stack.pop()
            if node_id in visited:
                continue
            visited.add(node_id)
            stack.extend(target for target in graph.get(node_id, []) if target not in visited)

        return visited

    def _start_node(self, workflow: Workflow) -> StartNode:
        for node in workflow.nodes:
            if isinstance(node, StartNode):
                return node
        raise WorkflowValidationError("workflow must contain exactly one start node")
