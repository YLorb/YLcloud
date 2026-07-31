from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from mini_agent_flow.engine.context import WorkflowContext
from mini_agent_flow.engine.graph_models import GraphNode
from mini_agent_flow.engine.resolver import VariableResolver
from mini_agent_flow.llm.base import LLMClient
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.secrets import SecretProvider
from mini_agent_flow.workers.isolated_process import IsolatedProcessRunner


@dataclass(frozen=True)
class NodeExecutionResult:
    disposition: Literal["success", "skipped"] = "success"
    input_data: dict[str, Any] = field(default_factory=dict)
    outputs: dict[str, Any] = field(default_factory=dict)
    publish_patch: dict[str, Any] = field(default_factory=dict)
    safe_metadata: dict[str, Any] = field(default_factory=dict)
    skip_reason: str | None = None


@dataclass(frozen=True)
class NodeRuntimeContext:
    context: WorkflowContext
    resolver: VariableResolver
    llm: LLMClient
    tool_registry: ToolRegistry
    allowed_permissions: set[str] | None
    max_risk_level: int | None
    isolation_risk_threshold: int = 8
    secret_provider: SecretProvider | None = None
    cancellation_check: Callable[[], bool] | None = None
    idempotency_key: str | None = None
    outcome_event: dict[str, Any] | None = None
    isolated_runner: IsolatedProcessRunner | None = None


class NodeHandler(Protocol):
    def execute(
        self, node: GraphNode, runtime: NodeRuntimeContext
    ) -> NodeExecutionResult: ...
