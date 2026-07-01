from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RetryPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")

    max_attempts: int = Field(default=1, ge=1, le=10)
    backoff_seconds: float = Field(default=0, ge=0)


class BaseNode(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    type: str
    description: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    retry: RetryPolicy | None = None


class StartNode(BaseNode):
    type: Literal["start"]
    next: str = Field(min_length=1)


class EndNode(BaseNode):
    type: Literal["end"]


class LLMNode(BaseNode):
    type: Literal["llm"]
    prompt: str = Field(min_length=1)
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    next: str = Field(min_length=1)


class ToolNode(BaseNode):
    type: Literal["tool"]
    tool: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    input: Any
    output: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    next: str = Field(min_length=1)


class ConditionNode(BaseNode):
    type: Literal["condition"]
    expression: str = Field(min_length=1)
    if_true: str = Field(min_length=1)
    if_false: str = Field(min_length=1)


class LoopNode(BaseNode):
    type: Literal["loop"]
    items: str = Field(min_length=1)
    item_var: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    body: list[str] = Field(min_length=1)
    next: str = Field(min_length=1)


WorkflowNode = Annotated[
    StartNode | EndNode | LLMNode | ToolNode | ConditionNode | LoopNode,
    Field(discriminator="type"),
]


class Workflow(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: Literal["1.0"]
    name: str = Field(min_length=1, pattern=r"^[A-Za-z_][A-Za-z0-9_-]*$")
    description: str | None = None
    inputs: dict[str, Any] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)
    nodes: list[WorkflowNode] = Field(min_length=2)

    @model_validator(mode="after")
    def validate_required_node_counts(self) -> "Workflow":
        start_nodes = [node for node in self.nodes if node.type == "start"]
        end_nodes = [node for node in self.nodes if node.type == "end"]

        if len(start_nodes) != 1:
            raise ValueError("workflow must contain exactly one start node")
        if not end_nodes:
            raise ValueError("workflow must contain at least one end node")

        node_ids = [node.id for node in self.nodes]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("workflow node ids must be unique")

        return self
