from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal
from uuid import uuid4


ArrivalReason = Literal[
    "BODY_COMPLETED", "CONTINUE", "BREAK", "FAILED", "CANCELLED"
]


class LoopControllerError(RuntimeError):
    pass


class LoopControllerState(str, Enum):
    CREATED = "CREATED"
    ITERATION_READY = "ITERATION_READY"
    BODY_RUNNING = "BODY_RUNNING"
    DECIDING = "DECIDING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


@dataclass(frozen=True)
class LoopReturnEvent:
    schema_version: str
    event_id: str
    run_id: str
    loop_id: str
    loop_invocation_id: str
    iteration: int
    attempt: int
    source_node_id: str
    source_edge_id: str
    arrival_reason: ArrivalReason
    collect_item: Any = None
    safe_error: str | None = None
    retryable: bool = False
    occurred_at_utc: str = ""
    controller_version: int = 0
    deduplication_key: str = ""

    @classmethod
    def create(
        cls,
        *,
        run_id: str,
        loop_id: str,
        loop_invocation_id: str,
        iteration: int,
        attempt: int,
        source_node_id: str,
        source_edge_id: str,
        arrival_reason: ArrivalReason,
        collect_item: Any = None,
        error: BaseException | None = None,
        retryable: bool = False,
        controller_version: int = 0,
    ) -> "LoopReturnEvent":
        key = (
            f"{loop_invocation_id}:{iteration}:{attempt}:{source_edge_id}:"
            f"{arrival_reason}"
        )
        return cls(
            schema_version="1",
            event_id=str(uuid4()),
            run_id=run_id,
            loop_id=loop_id,
            loop_invocation_id=loop_invocation_id,
            iteration=iteration,
            attempt=attempt,
            source_node_id=source_node_id,
            source_edge_id=source_edge_id,
            arrival_reason=arrival_reason,
            collect_item=collect_item,
            safe_error=str(error) if error else None,
            retryable=retryable,
            occurred_at_utc=datetime.now(timezone.utc).isoformat(),
            controller_version=controller_version,
            deduplication_key=key,
        )


@dataclass
class IterationFrame:
    iteration: int
    attempt: int = 1
    received_event_ids: set[str] = field(default_factory=set)
    received_deduplication_keys: set[str] = field(default_factory=set)


class LoopController:
    """首版同步 foreach Controller；continue/break 明确拒绝。"""

    def __init__(self, *, run_id: str, loop_id: str, item_count: int) -> None:
        self.run_id = run_id
        self.loop_id = loop_id
        self.item_count = item_count
        self.loop_invocation_id = str(uuid4())
        self.state = LoopControllerState.CREATED
        self.version = 0
        self.frame: IterationFrame | None = None
        self.accepted_events: list[LoopReturnEvent] = []

    def begin_iteration(self, iteration: int) -> None:
        if self.state in {
            LoopControllerState.COMPLETED,
            LoopControllerState.FAILED,
            LoopControllerState.CANCELLED,
        }:
            raise LoopControllerError("terminal loop controller cannot start an iteration")
        self.frame = IterationFrame(iteration=iteration)
        self.state = LoopControllerState.BODY_RUNNING
        self.version += 1

    def accept(self, event: LoopReturnEvent) -> str:
        if any(
            accepted.event_id == event.event_id
            or accepted.deduplication_key == event.deduplication_key
            for accepted in self.accepted_events
        ):
            return "DUPLICATE_REJECTED"
        frame = self.frame
        if frame is None or self.state != LoopControllerState.BODY_RUNNING:
            raise LoopControllerError("loop return arrived while body is not running")
        if event.loop_invocation_id != self.loop_invocation_id:
            raise LoopControllerError("loop return invocation does not match")
        if event.iteration != frame.iteration or event.attempt != frame.attempt:
            return "LATE_REJECTED"
        if (
            event.event_id in frame.received_event_ids
            or event.deduplication_key in frame.received_deduplication_keys
        ):
            return "DUPLICATE_REJECTED"
        frame.received_event_ids.add(event.event_id)
        frame.received_deduplication_keys.add(event.deduplication_key)
        self.accepted_events.append(event)
        self.state = LoopControllerState.DECIDING
        self.version += 1
        if event.arrival_reason in {"CONTINUE", "BREAK"}:
            raise LoopControllerError(
                f"{event.arrival_reason.lower()} is not supported in foreach v1"
            )
        if event.arrival_reason == "FAILED":
            self.state = LoopControllerState.FAILED
            return "FAILED"
        if event.arrival_reason == "CANCELLED":
            self.state = LoopControllerState.CANCELLED
            return "CANCELLED"
        if event.arrival_reason != "BODY_COMPLETED":
            raise LoopControllerError(f"unknown loop return reason: {event.arrival_reason}")
        if frame.iteration + 1 >= self.item_count:
            self.state = LoopControllerState.COMPLETED
            return "COMPLETE"
        self.state = LoopControllerState.ITERATION_READY
        return "NEXT_ITERATION"
