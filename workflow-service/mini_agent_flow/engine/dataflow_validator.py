from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from mini_agent_flow.engine.execution_plan import ExecutionPlan
from mini_agent_flow.engine.graph_models import (
    GraphLLMNode,
    GraphLoopNode,
    GraphMergeNode,
    GraphToolNode,
)
from mini_agent_flow.engine.resolver import VariableResolveError, VariableResolver


class DataflowValidationError(ValueError):
    """Graph 的字段来源无法在执行前证明安全。"""


@dataclass(frozen=True)
class NodeFieldAvailability:
    must: frozenset[str]
    may: frozenset[str]


@dataclass(frozen=True)
class DataflowReport:
    availability_by_node: dict[str, NodeFieldAvailability]
    publishers_by_field: dict[str, tuple[str, ...]]


class StaticDataflowValidator:
    """对公共 State、模板引用与 Edge 条件做保守 must/may 前向分析。"""

    def analyze(self, plan: ExecutionPlan) -> DataflowReport:
        workflow = plan.workflow
        graph = plan.compiled_graph.condensed_graph
        initial_must = set(workflow.inputs)
        initial_may = set(workflow.inputs) | set(workflow.state)
        for name, spec in workflow.state.items():
            if "default" in spec.model_fields_set or spec.nullable:
                initial_must.add(name)

        publishers: dict[str, list[str]] = {}
        for node in workflow.nodes:
            for field in self._published_fields(node):
                publishers.setdefault(field, []).append(node.id)
        if workflow.metadata.get("compiled_from_version") != "1.0":
            self._validate_publish_conflicts(workflow, publishers)

        availability: dict[str, NodeFieldAvailability] = {}
        entries: dict[str, NodeFieldAvailability] = {}
        for representative in plan.outer_order:
            predecessors = list(graph.predecessors(representative))
            if not predecessors:
                must, may = set(initial_must), set(initial_may)
            else:
                predecessor_states = [availability[item] for item in predecessors]
                must = set.intersection(
                    *(set(item.must) for item in predecessor_states)
                )
                may = set.union(*(set(item.may) for item in predecessor_states))
                must.update(initial_must)
                may.update(initial_may)
            entry = NodeFieldAvailability(frozenset(must), frozenset(may))
            entries[representative] = entry
            node = plan.nodes_by_id[representative]
            self._validate_node_references(node, entry)
            published = self._published_fields(node)
            availability[representative] = NodeFieldAvailability(
                must=frozenset(must | published), may=frozenset(may | published)
            )

        self._validate_edge_references(plan, availability)
        self._validate_loop_references(plan, entries)
        return DataflowReport(
            availability_by_node=availability,
            publishers_by_field={
                field: tuple(sorted(node_ids)) for field, node_ids in publishers.items()
            },
        )

    def _validate_node_references(
        self, node: object, available: NodeFieldAvailability
    ) -> None:
        values: list[object] = []
        if isinstance(node, GraphLLMNode):
            values.append(node.prompt)
        elif isinstance(node, GraphToolNode):
            values.append(node.input)
        elif isinstance(node, GraphLoopNode):
            values.append(node.items)
        skip_if = getattr(node, "skip_if", None)
        if skip_if is not None:
            values.extend([skip_if.source, skip_if.value])
        refs: set[str] = set()
        for value in values:
            refs.update(self._refs(value))
        refs -= set(getattr(node, "outcome_inputs", {}).keys())
        missing = sorted(ref for ref in refs if ref not in available.must)
        if missing:
            raise DataflowValidationError(
                f"node {getattr(node, 'id', '?')!r} references fields that are not "
                f"guaranteed available: {', '.join(missing)}"
            )

    def _validate_edge_references(
        self,
        plan: ExecutionPlan,
        availability: dict[str, NodeFieldAvailability],
    ) -> None:
        for edge in plan.workflow.edges:
            if edge.condition is None:
                continue
            source = plan.compiled_graph.representative_by_node[edge.source]
            if source != edge.source:
                # Loop 内部 Edge 使用每轮局部可用性，在下方单独校验。
                continue
            available = availability.get(source)
            if available is None:
                continue
            refs = self._refs(edge.condition.source) | self._refs(edge.condition.value)
            missing = sorted(ref for ref in refs if ref not in available.must)
            if missing:
                raise DataflowValidationError(
                    f"edge {edge.id!r} references fields that are not guaranteed "
                    f"available: {', '.join(missing)}"
                )

    def _validate_loop_references(
        self,
        plan: ExecutionPlan,
        outer_entries: dict[str, NodeFieldAvailability],
    ) -> None:
        for loop_id, region in plan.compiled_graph.loop_regions.items():
            loop = plan.nodes_by_id[loop_id]
            assert isinstance(loop, GraphLoopNode)
            entry = outer_entries[loop_id]
            base_must = set(entry.must) | {loop.item_var}
            base_may = set(entry.may) | {loop.item_var}
            if loop.index_var:
                base_must.add(loop.index_var)
                base_may.add(loop.index_var)
            local: dict[str, NodeFieldAvailability] = {}
            internal = set(region.internal_order)
            for node_id in region.internal_order:
                predecessors = {
                    edge.source
                    for edge in plan.workflow.edges
                    if edge.target == node_id and edge.source in internal
                }
                if predecessors:
                    states = [local[item] for item in predecessors]
                    must = set.intersection(*(set(item.must) for item in states)) | base_must
                    may = set.union(*(set(item.may) for item in states)) | base_may
                else:
                    must, may = set(base_must), set(base_may)
                node = plan.nodes_by_id[node_id]
                self._validate_node_references(
                    node, NodeFieldAvailability(frozenset(must), frozenset(may))
                )
                published = self._published_fields(node)
                local[node_id] = NodeFieldAvailability(
                    frozenset(must | published), frozenset(may | published)
                )
                for edge in plan.workflow.edges:
                    if edge.source != node_id or edge.condition is None:
                        continue
                    refs = self._refs(edge.condition.source) | self._refs(
                        edge.condition.value
                    )
                    missing = sorted(
                        ref for ref in refs if ref not in local[node_id].must
                    )
                    if missing:
                        raise DataflowValidationError(
                            f"edge {edge.id!r} references fields that are not guaranteed "
                            f"available: {', '.join(missing)}"
                        )

    def _refs(self, value: object) -> set[str]:
        resolver = VariableResolver()
        if isinstance(value, str):
            try:
                return {
                    path.split(".", 1)[0]
                    for path in resolver.extract_variables(value)
                }
            except VariableResolveError as exc:
                raise DataflowValidationError(str(exc)) from exc
        if isinstance(value, list):
            refs: set[str] = set()
            for item in value:
                refs.update(self._refs(item))
            return refs
        if isinstance(value, dict):
            refs: set[str] = set()
            for item in value.values():
                refs.update(self._refs(item))
            return refs
        return set()

    def _published_fields(self, node: object) -> set[str]:
        fields: set[str] = set()
        if isinstance(node, (GraphLLMNode, GraphToolNode, GraphMergeNode)) and node.publish:
            fields.add(node.output)
        if isinstance(node, GraphLoopNode) and node.collect and node.collect.publish:
            fields.add(node.collect.target)
        if hasattr(node, "publish_mapping"):
            fields.update(getattr(node, "publish_mapping").values())
        return fields

    def _validate_publish_conflicts(
        self, workflow: Any, publishers: dict[str, list[str]]
    ) -> None:
        nodes = {node.id: node for node in workflow.nodes}
        for field, node_ids in publishers.items():
            if len(node_ids) <= 1:
                continue
            if not any(isinstance(nodes[node_id], GraphMergeNode) for node_id in node_ids):
                raise DataflowValidationError(
                    f"public state field {field!r} has multiple publishers without merge: "
                    f"{', '.join(sorted(node_ids))}"
                )
