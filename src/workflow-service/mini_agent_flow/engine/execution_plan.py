from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping

from mini_agent_flow.engine.graph_analyzer import CompiledGraph, NetworkXGraphAnalyzer
from mini_agent_flow.engine.graph_models import GraphEdge, GraphNode, GraphWorkflow
from mini_agent_flow.engine.schema_normalizer import SchemaNormalizer


@dataclass(frozen=True)
class EdgeIndex:
    by_id: Mapping[str, GraphEdge]
    outgoing_by_source: Mapping[str, tuple[GraphEdge, ...]]
    incoming_by_target: Mapping[str, tuple[GraphEdge, ...]]

    @classmethod
    def build(cls, edges: list[GraphEdge]) -> "EdgeIndex":
        by_id: dict[str, GraphEdge] = {}
        outgoing: dict[str, list[GraphEdge]] = {}
        incoming: dict[str, list[GraphEdge]] = {}
        for edge in edges:
            if edge.id is None:
                raise ValueError("edge id must be normalized before building an execution plan")
            by_id[edge.id] = edge
            outgoing.setdefault(edge.source, []).append(edge)
            incoming.setdefault(edge.target, []).append(edge)
        for source_edges in outgoing.values():
            source_edges.sort(key=lambda edge: (edge.priority, edge.id or ""))
        return cls(
            by_id=MappingProxyType(by_id),
            outgoing_by_source=MappingProxyType(
                {key: tuple(value) for key, value in outgoing.items()}
            ),
            incoming_by_target=MappingProxyType(
                {key: tuple(value) for key, value in incoming.items()}
            ),
        )
    def outgoing(self, source: str, *kinds: str) -> tuple[GraphEdge, ...]:
        edges = self.outgoing_by_source.get(source, ())
        if not kinds:
            return edges
        allowed = set(kinds)
        return tuple(edge for edge in edges if edge.kind in allowed)


@dataclass(frozen=True)
class ExecutionPlan:
    plan_version: str
    workflow: GraphWorkflow
    compiled_graph: CompiledGraph
    nodes_by_id: Mapping[str, GraphNode]
    edge_index: EdgeIndex
    state_schema: Mapping[str, dict]
    source_version: str

    @property
    def outer_order(self) -> tuple[str, ...]:
        return self.compiled_graph.topological_order


class ExecutionPlanCompiler:
    def __init__(
        self,
        *,
        analyzer: NetworkXGraphAnalyzer | None = None,
        schema_normalizer: SchemaNormalizer | None = None,
    ) -> None:
        self.analyzer = analyzer or NetworkXGraphAnalyzer()
        self.schema_normalizer = schema_normalizer or SchemaNormalizer()

    def compile(self, workflow: GraphWorkflow) -> ExecutionPlan:
        compiled = self.analyzer.analyze(workflow)
        nodes = MappingProxyType({node.id: node for node in workflow.nodes})
        source_version = str(workflow.metadata.get("compiled_from_version", workflow.version))
        return ExecutionPlan(
            plan_version="1",
            workflow=workflow,
            compiled_graph=compiled,
            nodes_by_id=nodes,
            edge_index=EdgeIndex.build(workflow.edges),
            state_schema=MappingProxyType(
                self.schema_normalizer.normalize_workflow_state(workflow)
            ),
            source_version=source_version,
        )
