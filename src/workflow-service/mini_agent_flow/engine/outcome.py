from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Callable, Literal
from uuid import uuid4

from mini_agent_flow.engine.execution_plan import EdgeIndex
from mini_agent_flow.engine.graph_models import GraphEdge


OutcomeType = Literal["success", "error", "timeout", "cancelled"]


@dataclass(frozen=True)
class OutcomeEvent:
    schema_version: str
    event_id: str
    run_id: str
    execution_id: str
    invocation_id: str
    node_id: str
    outcome: OutcomeType
    reason_code: str
    attempt: int
    occurred_at_utc: str
    elapsed_ms: float
    deadline_at_utc: str | None = None
    safe_error_type: str | None = None
    safe_error_message: str | None = None
    retryable: bool = False
    handled: bool = False

    @classmethod
    def create(
        cls,
        *,
        run_id: str,
        execution_id: str,
        invocation_id: str,
        node_id: str,
        outcome: OutcomeType,
        reason_code: str,
        attempt: int,
        elapsed_ms: float,
        error: BaseException | None = None,
        retryable: bool = False,
    ) -> "OutcomeEvent":
        return cls(
            schema_version="1",
            event_id=str(uuid4()),
            run_id=run_id,
            execution_id=execution_id,
            invocation_id=invocation_id,
            node_id=node_id,
            outcome=outcome,
            reason_code=reason_code,
            attempt=attempt,
            occurred_at_utc=datetime.now(timezone.utc).isoformat(),
            elapsed_ms=elapsed_ms,
            safe_error_type=type(error).__name__ if error else None,
            safe_error_message=str(error) if error else None,
            retryable=retryable,
        )

    def to_safe_dict(self) -> dict[str, object]:
        return asdict(self)


class OutcomeRouter:
    def __init__(self, edge_index: EdgeIndex) -> None:
        self.edge_index = edge_index

    def success_edges(
        self,
        source: str,
        predicate: Callable[[GraphEdge], bool],
    ) -> tuple[GraphEdge, ...]:
        selected = tuple(
            edge
            for edge in self.edge_index.outgoing(
                source, "flow", "end", "loop_enter", "loop_exit"
            )
            if edge.condition is None or predicate(edge)
        )
        if selected:
            return selected
        return self.edge_index.outgoing(source, "default")

    def error_edge(
        self,
        source: str,
        predicate: Callable[[GraphEdge], bool],
    ) -> GraphEdge | None:
        for edge in self.edge_index.outgoing(source, "error"):
            if edge.condition is None or predicate(edge):
                return edge
        return None

    def timeout_edge(self, source: str) -> GraphEdge | None:
        edges = self.edge_index.outgoing(source, "timeout")
        return edges[0] if edges else None

    def cancel_edge(self, source: str) -> GraphEdge | None:
        edges = self.edge_index.outgoing(source, "cancel")
        return edges[0] if edges else None
