from __future__ import annotations

from dataclasses import dataclass

import networkx as nx

from mini_agent_flow.engine.graph_models import (
    GraphEndNode,
    GraphLLMNode,
    GraphLoopNode,
    GraphMergeNode,
    GraphStartNode,
    GraphToolNode,
    GraphWorkflow,
)


class GraphAnalysisError(ValueError):
    """Graph Workflow 结构无法安全编译时抛出。"""


@dataclass(frozen=True)
class LoopRegion:
    loop_node_id: str
    member_node_ids: frozenset[str]
    internal_order: tuple[str, ...]


@dataclass(frozen=True)
class CompiledGraph:
    workflow: GraphWorkflow
    graph: nx.DiGraph
    condensed_graph: nx.DiGraph
    topological_order: tuple[str, ...]
    representative_by_node: dict[str, str]
    loop_regions: dict[str, LoopRegion]


class NetworkXGraphAnalyzer:
    """使用 NetworkX 完成 v2 图校验、SCC 缩点与稳定拓扑排序。"""

    def analyze(self, workflow: GraphWorkflow) -> CompiledGraph:
        graph = self._build_graph(workflow)
        self._validate_node_roles(workflow, graph)
        self._validate_output_references(workflow, graph)
        self._validate_reachability(workflow, graph)
        representative_by_node, loop_regions = self._analyze_components(workflow, graph)
        declared_loops = {
            node.id for node in workflow.nodes if isinstance(node, GraphLoopNode)
        }
        inactive_loops = sorted(declared_loops - set(loop_regions))
        if inactive_loops:
            raise GraphAnalysisError(
                f"loop nodes must belong to a cyclic SCC: {', '.join(inactive_loops)}"
            )
        condensed = self._build_condensed_graph(graph, representative_by_node)

        if not nx.is_directed_acyclic_graph(condensed):
            raise GraphAnalysisError("condensed workflow graph must be a DAG")

        order = tuple(
            nx.lexicographical_topological_sort(condensed, key=self._stable_node_key)
        )
        return CompiledGraph(
            workflow=workflow,
            graph=graph,
            condensed_graph=condensed,
            topological_order=order,
            representative_by_node=representative_by_node,
            loop_regions=loop_regions,
        )

    def _build_graph(self, workflow: GraphWorkflow) -> nx.DiGraph:
        graph = nx.DiGraph()
        graph.add_nodes_from(node.id for node in workflow.nodes)
        node_ids = set(graph.nodes)

        for edge in workflow.edges:
            if edge.source not in node_ids or edge.target not in node_ids:
                raise GraphAnalysisError(
                    f"workflow edge points to unknown node: {edge.source} -> {edge.target}"
                )
            graph.add_edge(edge.source, edge.target)

        defaults_by_source: dict[str, int] = {}
        for edge in workflow.edges:
            if edge.kind == "default":
                defaults_by_source[edge.source] = defaults_by_source.get(edge.source, 0) + 1
        invalid_defaults = sorted(
            source for source, count in defaults_by_source.items() if count > 1
        )
        if invalid_defaults:
            raise GraphAnalysisError(
                f"node may define at most one default edge: {', '.join(invalid_defaults)}"
            )

        return graph

    def _validate_node_roles(self, workflow: GraphWorkflow, graph: nx.DiGraph) -> None:
        start = next(node.id for node in workflow.nodes if isinstance(node, GraphStartNode))
        if graph.in_degree(start) != 0:
            raise GraphAnalysisError("start node cannot have incoming edges")
        invalid_ends = sorted(
            node.id
            for node in workflow.nodes
            if isinstance(node, GraphEndNode) and graph.out_degree(node.id) != 0
        )
        if invalid_ends:
            raise GraphAnalysisError(
                f"end nodes cannot have outgoing edges: {', '.join(invalid_ends)}"
            )

        unconditional_by_source = {
            edge.source
            for edge in workflow.edges
            if edge.kind in {
                "flow",
                "end",
                "loop_enter",
                "loop_body_start",
                "loop_return",
                "loop_exit",
            }
            and edge.condition is None
        }
        redundant_defaults = sorted(
            {edge.source for edge in workflow.edges if edge.kind == "default"}
            & unconditional_by_source
        )
        if redundant_defaults:
            raise GraphAnalysisError(
                f"default edge is unreachable when an unconditional edge exists: {', '.join(redundant_defaults)}"
            )

    def _validate_output_references(
        self, workflow: GraphWorkflow, graph: nx.DiGraph
    ) -> None:
        nodes = {node.id: node for node in workflow.nodes}
        output_by_node = {
            node.id: node.output
            for node in workflow.nodes
            if isinstance(node, (GraphLLMNode, GraphToolNode, GraphMergeNode))
        }
        output_by_node.update(
            {
                node.id: node.collect.target
                for node in workflow.nodes
                if isinstance(node, GraphLoopNode) and node.collect is not None
            }
        )
        for node in workflow.nodes:
            for reference in node.skip_output_mapping.values():
                source_node, source_output = reference.split(".", 1)
                if output_by_node.get(source_node) != source_output:
                    raise GraphAnalysisError(
                        f"skip output mapping references unknown node output: {reference}"
                    )
                if source_node == node.id or not nx.has_path(graph, source_node, node.id):
                    raise GraphAnalysisError(
                        f"skip output source must be an upstream node: {reference}"
                    )
            if isinstance(node, GraphMergeNode):
                for reference in node.inputs.values():
                    if output_by_node.get(reference.node) != reference.output:
                        raise GraphAnalysisError(
                            f"merge references unknown node output: {reference.node}.{reference.output}"
                        )
            if isinstance(node, GraphLoopNode) and node.collect:
                if output_by_node.get(node.collect.node) != node.collect.output:
                    raise GraphAnalysisError(
                        f"loop collect references unknown node output: {node.collect.node}.{node.collect.output}"
                    )

    def _validate_reachability(self, workflow: GraphWorkflow, graph: nx.DiGraph) -> None:
        start = next(node.id for node in workflow.nodes if node.type == "start")
        reachable = {start, *nx.descendants(graph, start)}
        unreachable = sorted(set(graph.nodes) - reachable)
        if unreachable:
            raise GraphAnalysisError(
                f"workflow contains unreachable nodes: {', '.join(unreachable)}"
            )
        end_ids = {node.id for node in workflow.nodes if node.type == "end"}
        if not end_ids.intersection(reachable):
            raise GraphAnalysisError("workflow must contain a path from start to end")

    def _analyze_components(
        self,
        workflow: GraphWorkflow,
        graph: nx.DiGraph,
    ) -> tuple[dict[str, str], dict[str, LoopRegion]]:
        nodes = {node.id: node for node in workflow.nodes}
        representative: dict[str, str] = {}
        loop_regions: dict[str, LoopRegion] = {}

        for component in nx.strongly_connected_components(graph):
            is_cycle = len(component) > 1 or any(graph.has_edge(node, node) for node in component)
            if not is_cycle:
                node_id = next(iter(component))
                representative[node_id] = node_id
                continue

            loop_nodes = [nodes[node_id] for node_id in component if isinstance(nodes[node_id], GraphLoopNode)]
            if len(loop_nodes) != 1:
                members = ", ".join(sorted(component))
                raise GraphAnalysisError(
                    f"cyclic component must contain exactly one explicit loop node: {members}"
                )
            loop_node = loop_nodes[0]
            self._validate_loop_region(loop_node, component, graph)
            internal_graph = graph.subgraph(set(component) - {loop_node.id}).copy()
            if not nx.is_directed_acyclic_graph(internal_graph):
                raise GraphAnalysisError(
                    f"loop body must become a DAG after removing loop controller: {loop_node.id}"
                )
            internal_order = tuple(
                nx.lexicographical_topological_sort(internal_graph, key=self._stable_node_key)
            )
            region = LoopRegion(
                loop_node_id=loop_node.id,
                member_node_ids=frozenset(component),
                internal_order=internal_order,
            )
            loop_regions[loop_node.id] = region
            for node_id in component:
                representative[node_id] = loop_node.id

        return representative, loop_regions

    def _validate_loop_region(
        self,
        loop_node: GraphLoopNode,
        component: set[str],
        graph: nx.DiGraph,
    ) -> None:
        if loop_node.body_entry not in component or loop_node.body_exit not in component:
            raise GraphAnalysisError(
                f"loop body_entry and body_exit must belong to its SCC: {loop_node.id}"
            )
        if loop_node.body_entry == loop_node.id or loop_node.body_exit == loop_node.id:
            raise GraphAnalysisError("loop body entry/exit cannot be the loop controller")
        if not graph.has_edge(loop_node.id, loop_node.body_entry):
            raise GraphAnalysisError(f"loop must point to body_entry: {loop_node.id}")
        if not graph.has_edge(loop_node.body_exit, loop_node.id):
            raise GraphAnalysisError(f"loop body_exit must return to loop: {loop_node.id}")

        internal_graph = graph.subgraph(set(component) - {loop_node.id}).copy()
        reachable_from_entry = {
            loop_node.body_entry,
            *nx.descendants(internal_graph, loop_node.body_entry),
        }
        unreachable_body = sorted(set(internal_graph.nodes) - reachable_from_entry)
        if unreachable_body:
            raise GraphAnalysisError(
                f"loop body contains nodes unreachable from body_entry: {', '.join(unreachable_body)}"
            )
        if loop_node.body_entry != loop_node.body_exit and not nx.has_path(
            internal_graph, loop_node.body_entry, loop_node.body_exit
        ):
            raise GraphAnalysisError(f"loop body_entry cannot reach body_exit: {loop_node.id}")
        if loop_node.collect and loop_node.collect.node not in component:
            raise GraphAnalysisError(
                f"loop collect source must belong to the loop SCC: {loop_node.id}"
            )

        external_in = [
            (source, target)
            for source, target in graph.in_edges(component)
            if source not in component and target in component
        ]
        if len(external_in) != 1 or external_in[0][1] != loop_node.id:
            raise GraphAnalysisError(
                f"loop must have exactly one external entry targeting its controller: {loop_node.id}"
            )
        external_out = [
            (source, target)
            for source, target in graph.out_edges(component)
            if source in component and target not in component
        ]
        if not external_out or any(source != loop_node.id for source, _ in external_out):
            raise GraphAnalysisError(
                f"loop external exits must originate from its controller: {loop_node.id}"
            )

    def _build_condensed_graph(
        self,
        graph: nx.DiGraph,
        representative: dict[str, str],
    ) -> nx.DiGraph:
        condensed = nx.DiGraph()
        condensed.add_nodes_from(set(representative.values()))
        for source, target in graph.edges:
            source_rep = representative[source]
            target_rep = representative[target]
            if source_rep != target_rep:
                condensed.add_edge(source_rep, target_rep)
        return condensed

    def _stable_node_key(self, node_id: str) -> tuple[tuple[int, object], ...]:
        """自然排序键，使 node_2 排在 node_10 前。"""

        import re

        parts = re.split(r"(\d+)", node_id)
        return tuple(
            (0, int(part)) if part.isdigit() else (1, part)
            for part in parts
            if part
        )
