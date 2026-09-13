from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


@dataclass(frozen=True)
class PollResult:
    scanned: int
    timed_out: int
    stale: int


class DeadlinePoller:
    def __init__(
        self,
        store: SQLiteRunControlStore,
        *,
        owner_id: str,
        lease_seconds: float = 6,
        batch_size: int = 100,
    ) -> None:
        self.store = store
        self.owner_id = owner_id
        self.lease_seconds = lease_seconds
        self.batch_size = batch_size

    def tick(self) -> PollResult:
        now = self.store.database_utc_now()
        lease_until = self._plus_seconds(now, self.lease_seconds)
        if not self.store.acquire_poller_lease(
            lease_name="deadline-poller",
            owner_id=self.owner_id,
            lease_until_utc=lease_until,
            now_utc=now,
        ):
            return PollResult(scanned=0, timed_out=0, stale=0)
        rows = self.store.list_expired_invocations(now, self.batch_size)
        timed_out = 0
        stale = 0
        for row in rows:
            reason = (
                "BUSINESS_DEADLINE"
                if row.get("deadline_at_utc") and row["deadline_at_utc"] <= now
                else "WORKER_LOST"
            )
            won = self.store.compare_and_set_invocation(
                row["invocation_id"],
                expected_version=int(row["version"]),
                from_statuses={"running"},
                to_status="timed_out",
                updates={"safe_error_summary": reason},
            )
            if won:
                timed_out += 1
                self.store.append_control_event(
                    {
                        "event_id": str(uuid4()),
                        "run_id": self._run_id_for_execution(row["execution_id"]),
                        "execution_id": row["execution_id"],
                        "invocation_id": row["invocation_id"],
                        "event_type": "invocation_timed_out",
                        "deduplication_key": f"timeout:{row['invocation_id']}:{row['version']}",
                        "safe_payload_json": {"reason": reason},
                        "occurred_at_utc": now,
                    }
                )
            else:
                stale += 1
        return PollResult(scanned=len(rows), timed_out=timed_out, stale=stale)

    def _run_id_for_execution(self, execution_id: str) -> str:
        with self.store.connection() as connection:
            row = connection.execute(
                "SELECT run_id FROM run_executions WHERE execution_id=?", (execution_id,)
            ).fetchone()
            if row is None:
                raise RuntimeError(f"execution is missing: {execution_id}")
            return str(row["run_id"])

    def _plus_seconds(self, value: str, seconds: float) -> str:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return (parsed + timedelta(seconds=seconds)).astimezone(timezone.utc).isoformat().replace(
            "+00:00", "Z"
        )
