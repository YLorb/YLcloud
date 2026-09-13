from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone


class DeadlineExceededError(TimeoutError):
    pass


class CancellationToken:
    def __init__(self) -> None:
        self._event = threading.Event()
        self._reason: str | None = None

    def cancel(self, reason: str) -> None:
        self._reason = reason
        self._event.set()

    @property
    def cancelled(self) -> bool:
        return self._event.is_set()

    @property
    def reason(self) -> str | None:
        return self._reason

    def raise_if_cancelled(self) -> None:
        if self.cancelled:
            raise DeadlineExceededError(self._reason or "operation cancelled")


@dataclass(frozen=True)
class EffectiveDeadline:
    wall_clock_utc: str
    monotonic_deadline: float

    def remaining_seconds(self) -> float:
        return max(0.0, self.monotonic_deadline - time.perf_counter())

    def expired(self) -> bool:
        return self.remaining_seconds() <= 0


class DeadlineController:
    def create(
        self,
        *,
        global_remaining_seconds: float,
        node_timeout_seconds: float,
        runtime_hard_remaining_seconds: float,
    ) -> EffectiveDeadline:
        seconds = min(
            global_remaining_seconds,
            node_timeout_seconds,
            runtime_hard_remaining_seconds,
        )
        if seconds <= 0:
            raise DeadlineExceededError("effective deadline is already exhausted")
        wall = datetime.now(timezone.utc) + timedelta(seconds=seconds)
        return EffectiveDeadline(
            wall_clock_utc=wall.isoformat().replace("+00:00", "Z"),
            monotonic_deadline=time.perf_counter() + seconds,
        )
