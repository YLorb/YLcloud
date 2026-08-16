from __future__ import annotations
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from mini_agent_flow.engine.graph_analyzer import GraphAnalysisError, NetworkXGraphAnalyzer
from mini_agent_flow.engine.graph_models import (
    GraphLLMNode,
    GraphLoopNode,
    GraphMergeNode,
    GraphToolNode,
    GraphWorkflow,
)
from mini_agent_flow.engine.models import (
    ConditionNode,
    LLMNode,
    LoopNode,
    StartNode,
    ToolNode,
    Workflow,
    WorkflowNode,
)
from mini_agent_flow.tools.spec import ToolSpec


class WorkflowValidationError(ValueError):
    """workflow 结构或语义非法时抛出的异常。

    这里不区分具体节点类型错误，调用方只需要知道：文件已经成功读取并解析，
    但 workflow 内容本身不能被安全、明确地执行。
    """


class WorkflowValidator:
    """Workflow 语义校验器。

    Pydantic 模型负责字段级结构校验，本类负责跨节点规则，例如边是否存在、
    所有节点是否可达、是否存在 start 到 end 的路径，以及 tool 是否在白名单中。
    """

    def __init__(
        self,
        allowed_tools: set[str] | None = None,
        *,
        allowed_permissions: set[str] | None = None,
        max_risk_level: int | None = None,
        tool_specs: dict[str, ToolSpec] | None = None,
    ) -> None:
        """创建校验器。

        allowed_tools 为 None 表示暂不限制工具名；传入集合时，tool 节点只能引用
        集合内的工具。allowed_permissions 和 max_risk_level 用于基于 ToolSpec
        的权限与风险检查。tool_specs 提供工具的元数据。
        """

        self.allowed_tools = allowed_tools
        self.allowed_permissions = allowed_permissions
        self.max_risk_level = max_risk_level
        self.tool_specs = tool_specs or {}

    def validate_file(self, path: str | Path) -> Workflow:
        """读取 workflow 文件并完成校验。

        这是兼容入口。文件读取与格式解析已经交给 WorkflowLoader，
        新代码应优先直接使用 Loader。
        """

        from mini_agent_flow.engine.loader import WorkflowLoader

        return WorkflowLoader(validator=self).load(path)

    def validate_data(self, data: dict[str, Any]) -> Workflow | GraphWorkflow:
        """校验已解析为 dict 的 workflow 数据并返回 Workflow 对象。"""

        if data.get("version") == "2.0":
            return self._validate_graph_data(data)

        try:
            workflow = Workflow.model_validate(data)
        except ValidationError as exc:
            raise WorkflowValidationError(str(exc)) from exc

        self._validate_edges(workflow)
        self._validate_allowed_tools(workflow)
        self._validate_tool_specs(workflow)
        self._validate_declared_outputs(workflow)
        self._validate_reachability(workflow)
        self._validate_path_to_end(workflow)
        return workflow

    def _validate_graph_data(self, data: dict[str, Any]) -> GraphWorkflow:
        """校验 Workflow v2 结构、工具安全与图语义。"""

        try:
            workflow = GraphWorkflow.model_validate(data)
        except ValidationError as exc:
            raise WorkflowValidationError(str(exc)) from exc

        self._validate_graph_allowed_tools(workflow)
        self._validate_graph_tool_specs(workflow)
        self._validate_graph_outputs(workflow)
        try:
            NetworkXGraphAnalyzer().analyze(workflow)
        except GraphAnalysisError as exc:
            raise WorkflowValidationError(str(exc)) from exc
        return workflow

    def _validate_graph_allowed_tools(self, workflow: GraphWorkflow) -> None:
        if self.allowed_tools is None:
            return
        invalid = sorted(
            {
                node.tool
                for node in workflow.nodes
                if isinstance(node, GraphToolNode) and node.tool not in self.allowed_tools
            }
        )
        if invalid:
            raise WorkflowValidationError(
                f"workflow references unsupported tools: {', '.join(invalid)}"
            )

    def _validate_graph_tool_specs(self, workflow: GraphWorkflow) -> None:
        if not self.tool_specs:
            return
        for node in workflow.nodes:
            if not isinstance(node, GraphToolNode):
                continue
            spec = self.tool_specs.get(node.tool)
            if spec is None:
                continue
            if self.allowed_permissions is not None and spec.permission not in self.allowed_permissions:
                raise WorkflowValidationError(
                    f"tool {node.tool!r} permission {spec.permission!r} is not allowed"
                )
            if self.max_risk_level is not None and spec.risk_level > self.max_risk_level:
                raise WorkflowValidationError(
                    f"tool {node.tool!r} risk level {spec.risk_level} exceeds max {self.max_risk_level}"
                )

    def _validate_graph_outputs(self, workflow: GraphWorkflow) -> None:
        duplicate_outputs = sorted(
            name for name in set(workflow.outputs) if workflow.outputs.count(name) > 1
        )
        if duplicate_outputs:
            raise WorkflowValidationError(
                f"workflow outputs contain duplicate keys: {', '.join(duplicate_outputs)}"
            )

        available = set(workflow.inputs) | set(workflow.state)
        for node in workflow.nodes:
            if isinstance(node, (GraphLLMNode, GraphToolNode, GraphMergeNode)) and node.publish:
                available.add(node.output)
            if isinstance(node, GraphLoopNode) and node.collect and node.collect.publish:
                available.add(node.collect.target)
        missing = sorted(set(workflow.outputs) - available)
        if missing:
            raise WorkflowValidationError(
                f"workflow outputs are not produced by published nodes, inputs or state: {', '.join(missing)}"
            )

    def _validate_edges(self, workflow: Workflow) -> None:
        """校验所有节点引用的目标节点都真实存在。"""

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
        """校验 tool 节点只能引用白名单中的工具。"""

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

    def _validate_tool_specs(self, workflow: Workflow) -> None:
        """基于 ToolSpec 校验 tool 节点的权限与风险等级。

        只有同时提供 tool_specs 且节点引用的工具存在对应 spec 时才会执行检查。
        allowed_permissions 为 None 时不限制权限；max_risk_level 为 None 时不限制风险。
        """

        if not self.tool_specs:
            return

        for node in workflow.nodes:
            if not isinstance(node, ToolNode):
                continue
            spec = self.tool_specs.get(node.tool)
            if spec is None:
                continue
            if self.allowed_permissions is not None and spec.permission not in self.allowed_permissions:
                raise WorkflowValidationError(
                    f"tool {node.tool!r} permission {spec.permission!r} is not allowed"
                )
            if self.max_risk_level is not None and spec.risk_level > self.max_risk_level:
                raise WorkflowValidationError(
                    f"tool {node.tool!r} risk level {spec.risk_level} exceeds max {self.max_risk_level}"
                )

    def _validate_declared_outputs(self, workflow: Workflow) -> None:
        """校验 workflow.outputs 声明的字段有明确来源。"""

        duplicate_outputs = sorted(
            output_name
            for output_name in set(workflow.outputs)
            if workflow.outputs.count(output_name) > 1
        )
        if duplicate_outputs:
            joined_outputs = ", ".join(duplicate_outputs)
            raise WorkflowValidationError(f"workflow outputs contain duplicate keys: {joined_outputs}")

        available_outputs = set(workflow.inputs)
        available_outputs.update(
            node.output
            for node in workflow.nodes
            if isinstance(node, (LLMNode, ToolNode))
        )
        missing_outputs = sorted(
            output_name
            for output_name in workflow.outputs
            if output_name not in available_outputs
        )
        if missing_outputs:
            joined_outputs = ", ".join(missing_outputs)
            raise WorkflowValidationError(f"workflow outputs are not produced by inputs or nodes: {joined_outputs}")

    def _validate_reachability(self, workflow: Workflow) -> None:
        """校验所有节点都能从 start 节点到达。

        不可达节点通常意味着 workflow 配置存在死配置或 AI 生成了多余节点；
        这类节点不应该被静默接受，否则后续调试会很困难。
        """

        graph = self._build_graph(workflow)
        start_id = self._start_node(workflow).id
        reachable = self._reachable_nodes(graph, start_id)
        all_ids = {node.id for node in workflow.nodes}
        unreachable = sorted(all_ids - reachable)

        if unreachable:
            raise WorkflowValidationError(f"workflow contains unreachable nodes: {', '.join(unreachable)}")

    def _validate_path_to_end(self, workflow: Workflow) -> None:
        """校验从 start 出发至少能到达一个 end 节点。"""

        graph = self._build_graph(workflow)
        start_id = self._start_node(workflow).id
        end_ids = {node.id for node in workflow.nodes if node.type == "end"}
        reachable = self._reachable_nodes(graph, start_id)

        if not end_ids.intersection(reachable):
            raise WorkflowValidationError("workflow must contain at least one path from start to end")

    def _build_graph(self, workflow: Workflow) -> dict[str, list[str]]:
        """把 workflow 节点转换成邻接表，供可达性检查使用。"""

        return {node.id: self._outgoing_targets(node) for node in workflow.nodes}

    def _outgoing_targets(self, node: WorkflowNode) -> list[str]:
        """返回一个节点可能流向的所有后继节点 id。"""

        if isinstance(node, (StartNode, LLMNode, ToolNode)):
            return [node.next]
        if isinstance(node, ConditionNode):
            return [node.if_true, node.if_false]
        if isinstance(node, LoopNode):
            return [*node.body, node.next]
        return []

    def _reachable_nodes(self, graph: dict[str, list[str]], start_id: str) -> set[str]:
        """从 start_id 做深度优先遍历，返回所有可达节点。"""

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
        """取出唯一 start 节点。

        模型层已经保证 start 数量合法，这里保留异常是为了让内部调用更稳健。
        """

        for node in workflow.nodes:
            if isinstance(node, StartNode):
                return node
        raise WorkflowValidationError("workflow must contain exactly one start node")
