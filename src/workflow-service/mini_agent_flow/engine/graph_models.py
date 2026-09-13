from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from mini_agent_flow.engine.models import RetryPolicy, VariableName


EdgeKind = Literal[
    "flow",
    "default",
    "error",
    "timeout",
    "end",
    "cancel",
    "loop_enter",
    "loop_body_start",
    "loop_return",
    "loop_exit",
]


class EdgeCondition(BaseModel):
    """受限的结构化条件，不执行 eval。"""

    model_config = ConfigDict(extra="forbid")

    source: Any
    operator: Literal[
        "truthy",
        "falsy",
        "equals",
        "not_equals",
        "greater_than",
        "greater_or_equal",
        "less_than",
        "less_or_equal",
        "contains",
        "in",
    ] = "truthy"
    value: Any = None


class GraphBaseNode(BaseModel):
    """Workflow v2 节点公共字段。控制流统一由 edges 描述。"""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    type: str
    description: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    retry: RetryPolicy | None = None
    timeout_seconds: float | None = Field(default=None, gt=0)
    input_schema: dict[str, Any] | None = None
    output_schema: dict[str, Any] | None = None
    skip_if: EdgeCondition | None = None
    publish_mapping: dict[str, str] = Field(default_factory=dict)
    outcome_inputs: dict[str, str] = Field(default_factory=dict)
    skip_output_attribution: Literal["upstream", "self"] = "upstream"
    skip_output_mapping: dict[str, str] = Field(default_factory=dict)


class GraphStartNode(GraphBaseNode):
    type: Literal["start"]


class GraphEndNode(GraphBaseNode):
    type: Literal["end"]


class GraphLLMNode(GraphBaseNode):
    type: Literal["llm"]
    prompt: str = Field(min_length=1)
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    publish: bool = False
    output_schema: dict[str, Any] = Field(default_factory=lambda: {"type": "any"})


class GraphToolNode(GraphBaseNode):
    type: Literal["tool"]
    tool: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    input: Any
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    publish: bool = False
    output_schema: dict[str, Any] = Field(default_factory=lambda: {"type": "any"})


class OutputReference(BaseModel):
    model_config = ConfigDict(extra="forbid")

    node: str = Field(min_length=1)
    output: str = Field(min_length=1)
    required: bool = True
    default: Any = None


class LoopCollectSpec(OutputReference):
    target: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    publish: bool = False
    output_schema: dict[str, Any] = Field(default_factory=lambda: {"type": "any"})


class GraphLoopNode(GraphBaseNode):
    type: Literal["loop"]
    items: Any
    item_var: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    index_var: str | None = Field(default=None, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    body_entry: str = Field(min_length=1)
    body_exit: str = Field(min_length=1)
    max_iterations: int = Field(default=100, ge=1, le=10_000)
    collect: LoopCollectSpec | None = None


class GraphMergeNode(GraphBaseNode):
    type: Literal["merge"]
    inputs: dict[str, OutputReference] = Field(min_length=1)
    strategy: Literal["object", "list", "first"] = "object"
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    publish: bool = True
    join_policy: Literal["all_activated"] = "all_activated"
    output_schema: dict[str, Any] = Field(default_factory=lambda: {"type": "any"})


GraphNode = Annotated[
    GraphStartNode
    | GraphEndNode
    | GraphLLMNode
    | GraphToolNode
    | GraphLoopNode
    | GraphMergeNode,
    Field(discriminator="type"),
]


class GraphEdge(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str | None = Field(default=None, min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    source: str = Field(alias="from", min_length=1)
    target: str = Field(alias="to", min_length=1)
    kind: EdgeKind = "flow"
    condition: EdgeCondition | None = None
    default: bool = False
    priority: int = 100
    required: bool = True
    metadata: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def normalize_legacy_default(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        normalized = dict(data)
        if normalized.get("default") is True and "kind" not in normalized:
            normalized["kind"] = "default"
        if normalized.get("kind") == "default" and "default" not in normalized:
            normalized["default"] = True
        return normalized

    @model_validator(mode="after")
    def validate_default_edge(self) -> "GraphEdge":
        if self.default != (self.kind == "default"):
            raise ValueError("default flag and edge kind must agree")
        if self.kind == "default" and self.condition is not None:
            raise ValueError("default edge cannot also define condition")
        if self.kind in {"timeout", "end"} and self.condition is not None:
            raise ValueError(f"{self.kind} edge cannot define condition")
        return self


class StateFieldSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["any", "string", "integer", "number", "boolean", "array", "object"] = "any"
    nullable: bool = False
    default: Any = None


class GraphWorkflow(BaseModel):
    """Workflow v2：节点描述行为，显式 edges 描述控制流。"""

    model_config = ConfigDict(extra="forbid")

    version: Literal["2.0"]
    name: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    description: str | None = None
    inputs: dict[str, Any] = Field(default_factory=dict)
    outputs: list[VariableName] = Field(default_factory=list)
    state: dict[str, StateFieldSpec] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)
    nodes: list[GraphNode] = Field(min_length=2)
    edges: list[GraphEdge] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_node_counts(self) -> "GraphWorkflow":
        starts = [node for node in self.nodes if node.type == "start"]
        ends = [node for node in self.nodes if node.type == "end"]
        if len(starts) != 1:
            raise ValueError("workflow must contain exactly one start node")
        if not ends:
            raise ValueError("workflow must contain at least one end node")
        node_ids = [node.id for node in self.nodes]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("workflow node ids must be unique")
        self._normalize_and_validate_edges()
        for node in self.nodes:
            if node.skip_output_attribution == "self" and not node.skip_output_mapping:
                raise ValueError(
                    f"self skip output attribution requires skip_output_mapping: {node.id}"
                )
            if node.skip_output_attribution == "upstream" and node.skip_output_mapping:
                raise ValueError(
                    f"upstream skip attribution cannot define skip_output_mapping: {node.id}"
                )
            for reference in node.skip_output_mapping.values():
                parts = reference.split(".")
                if len(parts) != 2 or not all(parts):
                    raise ValueError(
                        f"skip output reference must be 'node.output': {node.id}"
                    )
        return self

    def _normalize_and_validate_edges(self) -> None:
        node_by_id = {node.id: node for node in self.nodes}
        seen_ids: set[str] = set()
        ordinal_by_pair: dict[tuple[str, str], int] = {}
        error_priorities: dict[str, set[int]] = {}
        timeout_sources: set[str] = set()
        cancel_sources: set[str] = set()
        loop_by_id = {
            node.id: node for node in self.nodes if isinstance(node, GraphLoopNode)
        }

        for edge in self.edges:
            pair = (edge.source, edge.target)
            ordinal = ordinal_by_pair.get(pair, 0)
            ordinal_by_pair[pair] = ordinal + 1
            if edge.id is None:
                edge.id = f"edge_{edge.source}_{edge.target}_{ordinal}"
            if edge.id in seen_ids:
                raise ValueError(f"workflow edge ids must be unique: {edge.id}")
            seen_ids.add(edge.id)

            target = node_by_id.get(edge.target)
            source_loop = loop_by_id.get(edge.source)
            target_loop = loop_by_id.get(edge.target)
            if edge.kind == "flow" and target_loop is not None:
                edge.kind = (
                    "loop_return"
                    if edge.source == target_loop.body_exit
                    else "loop_enter"
                )
            elif edge.kind == "flow" and source_loop is not None:
                edge.kind = (
                    "loop_body_start"
                    if edge.target == source_loop.body_entry
                    else "loop_exit"
                )
            elif isinstance(target, GraphEndNode) and edge.kind == "flow":
                edge.kind = "end"
            if edge.kind == "loop_enter" and target_loop is None:
                raise ValueError("loop_enter edge must target a loop controller")
            if edge.kind == "loop_body_start" and (
                source_loop is None or edge.target != source_loop.body_entry
            ):
                raise ValueError("loop_body_start must connect controller to body_entry")
            if edge.kind == "loop_return" and (
                target_loop is None or edge.source != target_loop.body_exit
            ):
                raise ValueError("loop_return must connect body_exit to controller")
            if edge.kind == "loop_exit" and source_loop is None:
                raise ValueError("loop_exit edge must originate from a loop controller")
            if edge.kind == "end" and not isinstance(target, GraphEndNode):
                raise ValueError("end edge must target an end node")
            if edge.kind == "error":
                priorities = error_priorities.setdefault(edge.source, set())
                if edge.priority in priorities:
                    raise ValueError(
                        f"error edge priorities must be unique for node {edge.source}: {edge.priority}"
                    )
                priorities.add(edge.priority)
            if edge.kind == "timeout":
                if edge.source in timeout_sources:
                    raise ValueError(
                        f"node may define at most one timeout edge: {edge.source}"
                    )
                timeout_sources.add(edge.source)
            if edge.kind == "cancel":
                if edge.source in cancel_sources:
                    raise ValueError(
                        f"node may define at most one cancel edge: {edge.source}"
                    )
                cancel_sources.add(edge.source)


from mini_agent_flow.engine.models import Workflow

WorkflowDefinition = Workflow | GraphWorkflow
