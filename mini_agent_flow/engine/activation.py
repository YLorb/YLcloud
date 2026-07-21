from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal


ActivationSourceStatus = Literal["success", "skipped", "recovered", "cancelled"]


@dataclass(frozen=True)
class SourceReference:
    node_id: str
    field: str
    value: Any


@dataclass(frozen=True)
class ActivationToken:
    edge_id: str
    source_node_id: str
    target_node_id: str
    source_status: ActivationSourceStatus
    field_refs: tuple[SourceReference, ...] = ()
    sequence: int = 0


class ActivationTracker:
    """保存本次运行实际命中的边，不从公共 Context 猜测激活状态。"""

    def __init__(self) -> None:
        self._tokens_by_target: dict[str, list[ActivationToken]] = {}
        self._sequence = 0

    def activate(
        self,
        *,
        edge_id: str,
        source_node_id: str,
        target_node_id: str,
        source_status: ActivationSourceStatus,
        field_refs: tuple[SourceReference, ...] = (),
    ) -> ActivationToken:
        self._sequence += 1
        token = ActivationToken(
            edge_id=edge_id,
            source_node_id=source_node_id,
            target_node_id=target_node_id,
            source_status=source_status,
            field_refs=field_refs,
            sequence=self._sequence,
        )
        self._tokens_by_target.setdefault(target_node_id, []).append(token)
        return token

    def tokens_for(self, node_id: str) -> tuple[ActivationToken, ...]:
        return tuple(self._tokens_by_target.get(node_id, ()))

    def is_active(self, node_id: str) -> bool:
        return bool(self._tokens_by_target.get(node_id))


class JoinCoordinator:
    """同步 DAG 中的 all_activated Join 计算。"""

    def arrivals(
        self,
        node_id: str,
        tracker: ActivationTracker,
    ) -> tuple[ActivationToken, ...]:
        return tracker.tokens_for(node_id)
