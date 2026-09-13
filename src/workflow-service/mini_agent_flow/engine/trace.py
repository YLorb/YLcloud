from __future__ import annotations

import copy
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from time import perf_counter
from typing import Any, Literal


TraceStatus = Literal[
    "success",
    "failed",
    "inactive",
    "skipped",
    "timed_out",
    "cancelled",
]
"""Trace 节点状态。

当前 Executor 只主动产生 success / failed，skipped 先作为后续 condition、loop、
DAG 分支语义的预留状态。
"""


SENSITIVE_KEYWORDS = (
    "api_key",
    "apikey",
    "token",
    "secret",
    "password",
    "authorization",
)
REDACTED_VALUE = "[REDACTED]"


def is_sensitive_key(key: Any) -> bool:
    if not isinstance(key, str):
        return False
    normalized_key = key.lower()
    return any(keyword in normalized_key for keyword in SENSITIVE_KEYWORDS)


def sanitize_for_audit(value: Any) -> Any:
    """递归复制并脱敏可持久化/展示的数据。"""

    if isinstance(value, dict):
        sanitized: dict[Any, Any] = {}
        for key, item in value.items():
            sanitized[key] = REDACTED_VALUE if is_sensitive_key(key) else sanitize_for_audit(item)
        return sanitized
    if isinstance(value, list):
        return [sanitize_for_audit(item) for item in value]
    if isinstance(value, tuple):
        return tuple(sanitize_for_audit(item) for item in value)
    return copy.deepcopy(value)


@dataclass(frozen=True)
class TraceError:
    """节点执行失败时记录的错误摘要。"""

    type: str
    message: str


@dataclass(frozen=True)
class ContextDiff:
    """节点执行前后 Context key 的变化。"""

    added: list[str]
    updated: list[str]
    removed: list[str]


@dataclass(frozen=True)
class TraceEvent:
    """单个节点的一次执行记录。"""

    node_id: str
    node_type: str
    status: TraceStatus
    input: dict[str, Any]
    output: dict[str, Any]
    error: TraceError | None
    context_before_keys: list[str]
    context_after_keys: list[str]
    context_diff: ContextDiff
    started_at: str
    ended_at: str
    duration_ms: float
    attempt: int = 1
    loop_id: str | None = None
    iteration: int | None = None
    metadata: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        """导出可序列化的 dict，方便测试、CLI 和后续 UI 使用。"""

        return asdict(self)


@dataclass(frozen=True)
class TraceSpan:
    """节点执行中的临时计时信息。"""

    started_at: str
    started_perf: float


class TraceRecorder:
    """内存 Trace Recorder。

    Recorder 只保存结构化 TraceEvent，不默认保存完整 context 快照，避免 workflow
    中的大对象在每个节点重复复制。Context 只记录 keys 和 diff。
    """

    def __init__(self) -> None:
        """创建空 TraceRecorder。"""

        self._events: list[TraceEvent] = []

    def start_span(self) -> TraceSpan:
        """开始记录一个节点的执行时间。"""

        return TraceSpan(
            started_at=datetime.now(timezone.utc).isoformat(),
            started_perf=perf_counter(),
        )

    def record_success(
        self,
        *,
        node_id: str,
        node_type: str,
        input_data: dict[str, Any],
        output_data: dict[str, Any],
        context_before: dict[str, Any],
        context_after: dict[str, Any],
        span: TraceSpan,
        attempt: int = 1,
        loop_id: str | None = None,
        iteration: int | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        """记录成功执行的节点。"""

        self._append_event(
            node_id=node_id,
            node_type=node_type,
            status="success",
            input_data=input_data,
            output_data=output_data,
            error=None,
            context_before=context_before,
            context_after=context_after,
            span=span,
            attempt=attempt,
            loop_id=loop_id,
            iteration=iteration,
            metadata=metadata,
        )

    def record_failure(
        self,
        *,
        node_id: str,
        node_type: str,
        input_data: dict[str, Any],
        error: BaseException,
        context_before: dict[str, Any],
        context_after: dict[str, Any],
        span: TraceSpan,
        attempt: int = 1,
        loop_id: str | None = None,
        iteration: int | None = None,
        status: Literal["failed", "timed_out", "cancelled"] = "failed",
        metadata: dict[str, Any] | None = None,
    ) -> None:
        """记录执行失败的节点。"""

        self._append_event(
            node_id=node_id,
            node_type=node_type,
            status=status,
            input_data=input_data,
            output_data={},
            error=TraceError(type=type(error).__name__, message=str(error)),
            context_before=context_before,
            context_after=context_after,
            span=span,
            attempt=attempt,
            loop_id=loop_id,
            iteration=iteration,
            metadata=metadata,
        )

    def record_skipped(
        self,
        *,
        node_id: str,
        node_type: str,
        input_data: dict[str, Any],
        context_before: dict[str, Any],
        context_after: dict[str, Any],
        span: TraceSpan,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> None:
        """记录图节点因未激活而跳过。"""

        self._append_event(
            node_id=node_id,
            node_type=node_type,
            status="skipped",
            input_data=input_data,
            output_data={},
            error=None,
            context_before=context_before,
            context_after=context_after,
            span=span,
            attempt=1,
            loop_id=loop_id,
            iteration=iteration,
        )

    def record_inactive(
        self,
        *,
        node_id: str,
        node_type: str,
        context_before: dict[str, Any],
        context_after: dict[str, Any],
        span: TraceSpan,
        loop_id: str | None = None,
        iteration: int | None = None,
    ) -> None:
        """记录节点在本次运行路径中未被激活。"""

        self._append_event(
            node_id=node_id,
            node_type=node_type,
            status="inactive",
            input_data={"reason": "node was not activated"},
            output_data={},
            error=None,
            context_before=context_before,
            context_after=context_after,
            span=span,
            attempt=1,
            loop_id=loop_id,
            iteration=iteration,
        )

    def to_list(self) -> list[dict[str, Any]]:
        """返回 TraceEvent 的 dict 副本列表。"""

        return [event.to_dict() for event in self._events]

    def to_v2_list(self) -> list[dict[str, Any]]:
        """返回带版本和稳定 sequence 的 Graph Trace v2。"""

        return [
            {
                "schema_version": "2.0",
                "sequence": sequence,
                **event.to_dict(),
            }
            for sequence, event in enumerate(self._events, start=1)
        ]
    def _append_event(
        self,
        *,
        node_id: str,
        node_type: str,
        status: TraceStatus,
        input_data: dict[str, Any],
        output_data: dict[str, Any],
        error: TraceError | None,
        context_before: dict[str, Any],
        context_after: dict[str, Any],
        span: TraceSpan,
        attempt: int,
        loop_id: str | None,
        iteration: int | None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        """构造并保存 TraceEvent。"""

        ended_at = datetime.now(timezone.utc).isoformat()
        duration_ms = (perf_counter() - span.started_perf) * 1000
        event = TraceEvent(
            node_id=node_id,
            node_type=node_type,
            status=status,
            input=self._sanitize(input_data),
            output=self._sanitize(output_data),
            error=error,
            context_before_keys=sorted(context_before),
            context_after_keys=sorted(context_after),
            context_diff=self._build_context_diff(context_before, context_after),
            started_at=span.started_at,
            ended_at=ended_at,
            duration_ms=duration_ms,
            attempt=attempt,
            loop_id=loop_id,
            iteration=iteration,
            metadata=self._sanitize(metadata) if metadata else None,
        )
        self._events.append(event)

    def _build_context_diff(
        self,
        before: dict[str, Any],
        after: dict[str, Any],
    ) -> ContextDiff:
        """计算节点执行前后 Context 的 key 变化。"""

        before_keys = set(before)
        after_keys = set(after)
        shared_keys = before_keys & after_keys
        return ContextDiff(
            added=sorted(after_keys - before_keys),
            updated=sorted(key for key in shared_keys if before[key] != after[key]),
            removed=sorted(before_keys - after_keys),
        )

    def _sanitize(self, value: Any) -> Any:
        """递归脱敏输入输出中的敏感字段。"""
        return sanitize_for_audit(value)

    def _is_sensitive_key(self, key: Any) -> bool:
        """判断字段名是否包含敏感关键词。"""

        return is_sensitive_key(key)


class V1TraceAdapter:
    """把内部 Trace v2 投影为永久兼容的 v1 Trace 结构。"""

    def project(self, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
        projected: list[dict[str, Any]] = []
        for event in events:
            item = {
                key: copy.deepcopy(value)
                for key, value in event.items()
                if key not in {"schema_version", "sequence"}
            }
            if item.get("status") == "inactive":
                item["status"] = "skipped"
            projected.append(item)
        return projected
