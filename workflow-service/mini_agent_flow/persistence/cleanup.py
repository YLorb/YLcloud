from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


@dataclass(frozen=True)
class CleanupResult:
    run_ids: tuple[str, ...]
    dry_run: bool

    @property
    def count(self) -> int:
        return len(self.run_ids)


class CleanupJob:
    def __init__(
        self,
        store: SQLiteRunControlStore,
        *,
        success_retention_days: int = 7,
        failure_retention_days: int = 30,
        batch_size: int = 100,
    ) -> None:
        if success_retention_days < 1 or failure_retention_days < 1 or batch_size < 1:
            raise ValueError("cleanup retention and batch size must be positive")
        self.store = store
        self.success_retention_days = success_retention_days
        self.failure_retention_days = failure_retention_days
        self.batch_size = batch_size

    def run(self, *, dry_run: bool = False, now: datetime | None = None) -> CleanupResult:
        now = now or datetime.now(timezone.utc)
        success_cutoff = self._format(now - timedelta(days=self.success_retention_days))
        failure_cutoff = self._format(now - timedelta(days=self.failure_retention_days))
        run_ids = self.store.delete_expired_runs(
            cutoff_by_status={
                "completed": success_cutoff,
                "completed_with_recovery": success_cutoff,
                "failed": failure_cutoff,
                "timed_out": failure_cutoff,
                "cancelled": failure_cutoff,
                "abandoned": failure_cutoff,
            },
            limit=self.batch_size,
            dry_run=dry_run,
        )
        return CleanupResult(run_ids=tuple(run_ids), dry_run=dry_run)

    def _format(self, value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
