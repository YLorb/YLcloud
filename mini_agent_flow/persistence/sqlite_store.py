from __future__ import annotations

import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from mini_agent_flow.persistence.migrations import MigrationRunner


class RunControlStoreError(RuntimeError):
    """SQLite Run Control 操作失败。"""


class SQLiteRunControlStore:
    _ALLOWED_UPDATE_FIELDS = {
        "finished_at_utc",
        "result_summary_json",
        "current_execution_id",
        "restart_count",
        "worker_id",
        "deadline_at_utc",
        "lease_until_utc",
        "heartbeat_at_utc",
        "cancel_requested_at_utc",
        "outcome_event_id",
        "safe_error_summary",
        "owner_id",
        "owner_lease_until_utc",
        "safe_to_restart",
        "updated_at_utc",
    }

    def __init__(
        self,
        database_path: str | Path,
        *,
        busy_timeout_seconds: float = 5,
        retries: int = 3,
    ) -> None:
        self.database_path = Path(database_path)
        self.busy_timeout_seconds = busy_timeout_seconds
        self.retries = retries
        if busy_timeout_seconds <= 0 or retries < 1:
            raise ValueError("busy timeout and retries must be positive")

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(
            self.database_path,
            timeout=self.busy_timeout_seconds,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute(f"PRAGMA busy_timeout={int(self.busy_timeout_seconds * 1000)}")
        try:
            yield connection
        finally:
            connection.close()

    def migrate(self) -> None:
        with self.connection() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            MigrationRunner().apply(connection)

    def database_utc_now(self) -> str:
        with self.connection() as connection:
            row = connection.execute(
                "SELECT strftime('%Y-%m-%dT%H:%M:%fZ','now') AS now"
            ).fetchone()
            return str(row["now"])

    def create_run(self, record: dict[str, Any]) -> None:
        record = {"workflow_json": "{}", **record}
        fields = (
            "run_id",
            "workflow_name",
            "workflow_version",
            "workflow_json",
            "status",
            "started_at_utc",
            "deadline_at_utc",
            "created_at_utc",
            "updated_at_utc",
        )
        self._insert("workflow_runs", record, fields)

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        with self.connection() as connection:
            row = connection.execute(
                "SELECT * FROM workflow_runs WHERE run_id = ?", (run_id,)
            ).fetchone()
            return dict(row) if row else None

    def create_execution(self, record: dict[str, Any]) -> None:
        fields = (
            "execution_id",
            "run_id",
            "epoch",
            "status",
            "owner_id",
            "owner_lease_until_utc",
            "started_at_utc",
        )
        self._insert("run_executions", record, fields)

    def create_invocation(self, record: dict[str, Any]) -> None:
        record = {
            "worker_id": None,
            "lease_until_utc": None,
            "heartbeat_at_utc": None,
            **record,
        }
        fields = (
            "invocation_id",
            "execution_id",
            "node_id",
            "iteration_path_json",
            "attempt",
            "status",
            "worker_id",
            "idempotent",
            "idempotency_key",
            "deadline_at_utc",
            "lease_until_utc",
            "heartbeat_at_utc",
            "created_at_utc",
            "updated_at_utc",
        )
        self._insert("node_invocations", record, fields)

    def get_execution(self, execution_id: str) -> dict[str, Any] | None:
        with self.connection() as connection:
            row = connection.execute(
                "SELECT * FROM run_executions WHERE execution_id=?", (execution_id,)
            ).fetchone()
            return dict(row) if row else None

    def list_runs(self, *, limit: int = 100) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM workflow_runs ORDER BY created_at_utc DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def list_executions(self, run_id: str) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM run_executions WHERE run_id=? ORDER BY epoch", (run_id,)
            ).fetchall()
            return [dict(row) for row in rows]

    def list_invocations(self, execution_id: str) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM node_invocations WHERE execution_id=? "
                "ORDER BY created_at_utc, node_id, attempt",
                (execution_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def has_non_idempotent_success(self, execution_id: str) -> bool:
        with self.connection() as connection:
            row = connection.execute(
                "SELECT 1 FROM node_invocations WHERE execution_id=? "
                "AND status IN ('success','skipped') AND idempotent=0 LIMIT 1",
                (execution_id,),
            ).fetchone()
            return row is not None

    def compare_and_set_execution(
        self,
        execution_id: str,
        *,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None = None,
    ) -> bool:
        return self._compare_and_set(
            table="run_executions",
            id_field="execution_id",
            identifier=execution_id,
            expected_version=expected_version,
            from_statuses=from_statuses,
            to_status=to_status,
            updates=updates,
        )

    def compare_and_set_run(
        self,
        run_id: str,
        *,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None = None,
    ) -> bool:
        return self._compare_and_set(
            table="workflow_runs",
            id_field="run_id",
            identifier=run_id,
            expected_version=expected_version,
            from_statuses=from_statuses,
            to_status=to_status,
            updates=updates,
        )

    def compare_and_set_invocation(
        self,
        invocation_id: str,
        *,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None = None,
    ) -> bool:
        return self._compare_and_set(
            table="node_invocations",
            id_field="invocation_id",
            identifier=invocation_id,
            expected_version=expected_version,
            from_statuses=from_statuses,
            to_status=to_status,
            updates=updates,
        )

    def acquire_poller_lease(
        self,
        *,
        lease_name: str,
        owner_id: str,
        lease_until_utc: str,
        now_utc: str,
    ) -> bool:
        def operation(connection: sqlite3.Connection) -> bool:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT owner_id, lease_until_utc, version FROM poller_leases WHERE lease_name = ?",
                (lease_name,),
            ).fetchone()
            if row is None:
                connection.execute(
                    "INSERT INTO poller_leases(lease_name, owner_id, lease_until_utc) VALUES (?, ?, ?)",
                    (lease_name, owner_id, lease_until_utc),
                )
                connection.execute("COMMIT")
                return True
            if row["owner_id"] != owner_id and row["lease_until_utc"] >= now_utc:
                connection.execute("ROLLBACK")
                return False
            cursor = connection.execute(
                "UPDATE poller_leases SET owner_id=?, lease_until_utc=?, version=version+1 "
                "WHERE lease_name=? AND version=?",
                (owner_id, lease_until_utc, lease_name, row["version"]),
            )
            connection.execute("COMMIT")
            return cursor.rowcount == 1

        return self._with_busy_retry(operation)

    def heartbeat(
        self,
        invocation_id: str,
        *,
        worker_id: str,
        expected_version: int,
        heartbeat_at_utc: str,
        lease_until_utc: str,
    ) -> bool:
        return self.compare_and_set_invocation(
            invocation_id,
            expected_version=expected_version,
            from_statuses={"running"},
            to_status="running",
            updates={
                "worker_id": worker_id,
                "heartbeat_at_utc": heartbeat_at_utc,
                "lease_until_utc": lease_until_utc,
            },
        )

    def apply_cancel_request(self, request_id: str, *, applied_at_utc: str) -> bool:
        def operation(connection: sqlite3.Connection) -> bool:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                "UPDATE cancel_requests SET status='applied', applied_at_utc=? "
                "WHERE request_id=? AND status='pending'",
                (applied_at_utc, request_id),
            )
            connection.execute("COMMIT")
            return cursor.rowcount == 1

        return self._with_busy_retry(operation)

    def list_trace_events(self, execution_id: str) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM trace_events WHERE execution_id=? ORDER BY sequence",
                (execution_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def list_expired_invocations(self, now_utc: str, limit: int = 100) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM node_invocations WHERE status='running' "
                "AND ((deadline_at_utc IS NOT NULL AND deadline_at_utc <= ?) "
                "OR (lease_until_utc IS NOT NULL AND lease_until_utc <= ?)) "
                "ORDER BY COALESCE(deadline_at_utc, lease_until_utc) LIMIT ?",
                (now_utc, now_utc, limit),
            ).fetchall()
            return [dict(row) for row in rows]

    def request_cancel(
        self,
        record: dict[str, Any],
    ) -> bool:
        fields = (
            "request_id",
            "run_id",
            "execution_id",
            "scope",
            "target_node_id",
            "status",
            "reason",
            "requested_at_utc",
        )
        try:
            self._insert("cancel_requests", record, fields)
            return True
        except sqlite3.IntegrityError:
            return False

    def pending_cancel_requests(self, execution_id: str) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM cancel_requests WHERE execution_id=? AND status='pending' "
                "ORDER BY requested_at_utc",
                (execution_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def append_control_event(self, event: dict[str, Any]) -> bool:
        fields = (
            "event_id",
            "run_id",
            "execution_id",
            "invocation_id",
            "event_type",
            "deduplication_key",
            "safe_payload_json",
            "occurred_at_utc",
        )
        prepared = dict(event)
        if not isinstance(prepared.get("safe_payload_json"), str):
            prepared["safe_payload_json"] = json.dumps(
                prepared.get("safe_payload_json", {}), ensure_ascii=False, sort_keys=True
            )
        try:
            self._insert("control_events", prepared, fields)
            return True
        except sqlite3.IntegrityError:
            return False

    def append_trace_batch(self, events: list[dict[str, Any]]) -> None:
        if not events:
            return
        columns = (
            "trace_event_id",
            "run_id",
            "execution_id",
            "sequence",
            "node_id",
            "status",
            "schema_version",
            "payload_json",
            "payload_truncated",
            "occurred_at_utc",
        )
        values = [tuple(event[name] for name in columns) for event in events]

        def operation(connection: sqlite3.Connection) -> None:
            connection.execute("BEGIN IMMEDIATE")
            connection.executemany(
                f"INSERT INTO trace_events({', '.join(columns)}) VALUES ({', '.join('?' for _ in columns)})",
                values,
            )
            connection.execute("COMMIT")

        self._with_busy_retry(operation)

    def save_context_snapshot(self, record: dict[str, Any]) -> None:
        self._insert(
            "context_snapshots",
            record,
            (
                "snapshot_id",
                "run_id",
                "execution_id",
                "sequence",
                "redacted_context_json",
                "byte_size",
                "created_at_utc",
            ),
        )

    def save_node_output(self, record: dict[str, Any]) -> None:
        self._insert(
            "node_outputs",
            record,
            (
                "output_id",
                "execution_id",
                "invocation_id",
                "node_id",
                "output_json",
                "byte_size",
                "created_at_utc",
            ),
        )

    def list_recoverable_executions(self, now_utc: str) -> list[dict[str, Any]]:
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM run_executions WHERE status='running' "
                "AND owner_lease_until_utc <= ? ORDER BY started_at_utc",
                (now_utc,),
            ).fetchall()
            return [dict(row) for row in rows]

    def delete_expired_runs(
        self,
        *,
        cutoff_by_status: dict[str, str],
        limit: int = 100,
        dry_run: bool = False,
    ) -> list[str]:
        clauses: list[str] = []
        parameters: list[Any] = []
        for status, cutoff in sorted(cutoff_by_status.items()):
            clauses.append("(status=? AND finished_at_utc IS NOT NULL AND finished_at_utc < ?)")
            parameters.extend([status, cutoff])
        if not clauses:
            return []
        query = (
            "SELECT run_id FROM workflow_runs WHERE "
            + " OR ".join(clauses)
            + " ORDER BY finished_at_utc LIMIT ?"
        )
        parameters.append(limit)

        def operation(connection: sqlite3.Connection) -> list[str]:
            connection.execute("BEGIN IMMEDIATE")
            run_ids = [
                str(row["run_id"])
                for row in connection.execute(query, parameters).fetchall()
            ]
            if run_ids and not dry_run:
                placeholders = ", ".join("?" for _ in run_ids)
                connection.execute(
                    f"DELETE FROM workflow_runs WHERE run_id IN ({placeholders})",
                    run_ids,
                )
            connection.execute("ROLLBACK" if dry_run else "COMMIT")
            return run_ids

        return self._with_busy_retry(operation)

    def _insert(self, table: str, record: dict[str, Any], fields: tuple[str, ...]) -> None:
        missing = [field for field in fields if field not in record]
        if missing:
            raise RunControlStoreError(f"missing {table} fields: {', '.join(missing)}")
        values = tuple(record[field] for field in fields)

        def operation(connection: sqlite3.Connection) -> None:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                f"INSERT INTO {table}({', '.join(fields)}) "
                f"VALUES ({', '.join('?' for _ in fields)})",
                values,
            )
            connection.execute("COMMIT")

        self._with_busy_retry(operation)

    def _compare_and_set(
        self,
        *,
        table: str,
        id_field: str,
        identifier: str,
        expected_version: int,
        from_statuses: set[str],
        to_status: str,
        updates: dict[str, Any] | None,
    ) -> bool:
        if not from_statuses:
            raise ValueError("CAS requires at least one source status")
        updates = dict(updates or {})
        invalid = set(updates) - self._ALLOWED_UPDATE_FIELDS
        if invalid:
            raise ValueError(f"unsafe CAS update fields: {', '.join(sorted(invalid))}")
        assignments = ["status=?", "version=version+1"]
        parameters: list[Any] = [to_status]
        for name, value in sorted(updates.items()):
            assignments.append(f"{name}=?")
            parameters.append(value)
        placeholders = ", ".join("?" for _ in from_statuses)
        parameters.extend([identifier, expected_version, *sorted(from_statuses)])
        sql = (
            f"UPDATE {table} SET {', '.join(assignments)} WHERE {id_field}=? "
            f"AND version=? AND status IN ({placeholders})"
        )

        def operation(connection: sqlite3.Connection) -> bool:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(sql, parameters)
            connection.execute("COMMIT")
            return cursor.rowcount == 1

        return self._with_busy_retry(operation)

    def _with_busy_retry(self, operation: Any) -> Any:
        last_error: sqlite3.OperationalError | None = None
        for attempt in range(self.retries):
            try:
                with self.connection() as connection:
                    return operation(connection)
            except sqlite3.OperationalError as exc:
                if "locked" not in str(exc).lower() and "busy" not in str(exc).lower():
                    raise
                last_error = exc
                if attempt + 1 < self.retries:
                    time.sleep(0.01 * (2**attempt))
        raise RunControlStoreError("SQLite remained busy after bounded retries") from last_error
