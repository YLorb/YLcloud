from __future__ import annotations

import hashlib
import json
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import uuid4

from mini_agent_flow.engine.graph_models import GraphNode, GraphToolNode, GraphWorkflow
from mini_agent_flow.engine.runtime_config import RuntimeConfig
from mini_agent_flow.engine.trace import sanitize_for_audit
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore
from mini_agent_flow.tools.registry import ToolRegistry


class RunControlError(RuntimeError):
    pass


@dataclass(frozen=True)
class InvocationHandle:
    invocation_id: str
    node_id: str
    attempt: int
    iteration_path_json: str
    idempotent: bool
    idempotency_key: str | None


class RunController:
    """把单次 Graph 执行的状态转换写入可选 RunControlStore。"""

    def __init__(
        self,
        *,
        workflow: GraphWorkflow,
        tool_registry: ToolRegistry,
        config: RuntimeConfig,
        store: SQLiteRunControlStore | None = None,
        run_id: str | None = None,
        execution_id: str | None = None,
        owner_id: str | None = None,
        epoch: int = 1,
        restart_existing: bool = False,
    ) -> None:
        self.workflow = workflow
        self.tool_registry = tool_registry
        self.config = config
        self.store = store
        self.run_id = run_id or str(uuid4())
        self.execution_id = execution_id or str(uuid4())
        self.owner_id = owner_id or f"runtime-{uuid4()}"
        self.epoch = epoch
        self.restart_existing = restart_existing
        self._run_version = 0
        self._execution_version = 0
        self._snapshot_sequence = 0
        self._started = False
        self._heartbeat_stop = threading.Event()
        self._heartbeat_thread: threading.Thread | None = None
        self._state_lock = threading.RLock()
        self._heartbeat_error: BaseException | None = None
        self._active_invocation_versions: dict[str, int] = {}

    def start(self) -> None:
        if self._started:
            raise RunControlError("run controller was already started")
        self._started = True
        if self.store is None:
            return
        now = self.store.database_utc_now()
        deadline = self._plus_seconds(now, self.config.max_duration_seconds)
        owner_lease = self._plus_seconds(now, self.config.lease_seconds)
        if not self.restart_existing:
            self.store.create_run(
                {
                    "run_id": self.run_id,
                    "workflow_name": self.workflow.name,
                    "workflow_version": self.workflow.version,
                    "workflow_json": self._json(
                        sanitize_for_audit(self.workflow.model_dump(mode="json", by_alias=True))
                    ),
                    "status": "pending",
                    "started_at_utc": now,
                    "deadline_at_utc": deadline,
                    "created_at_utc": now,
                    "updated_at_utc": now,
                }
            )
        self.store.create_execution(
            {
                "execution_id": self.execution_id,
                "run_id": self.run_id,
                "epoch": self.epoch,
                "status": "running",
                "owner_id": self.owner_id,
                "owner_lease_until_utc": owner_lease,
                "started_at_utc": now,
            }
        )
        run = self.store.get_run(self.run_id)
        expected_version = int(run["version"]) if run else 0
        from_statuses = {"running", "abandoned"} if self.restart_existing else {"pending"}
        updates: dict[str, Any] = {
            "current_execution_id": self.execution_id,
            "updated_at_utc": now,
        }
        if self.restart_existing:
            updates["restart_count"] = int(run["restart_count"]) + 1
            updates["deadline_at_utc"] = deadline
            updates["finished_at_utc"] = None
        if not self.store.compare_and_set_run(
            self.run_id,
            expected_version=expected_version,
            from_statuses=from_statuses,
            to_status="running",
            updates=updates,
        ):
            self.store.compare_and_set_execution(
                self.execution_id,
                expected_version=0,
                from_statuses={"running"},
                to_status="abandoned",
                updates={"finished_at_utc": now, "safe_to_restart": 1},
            )
            raise RunControlError("failed to activate persisted workflow run")
        self._run_version = expected_version + 1
        self._start_heartbeat()

    def check_health(self) -> None:
        if self._heartbeat_error is not None:
            raise RunControlError("run control heartbeat failed") from self._heartbeat_error

    def consume_cancel_request(self, node_id: str) -> dict[str, Any] | None:
        self.check_health()
        if self.store is None:
            return None
        for request in self.store.pending_cancel_requests(self.execution_id):
            if request["scope"] == "run" or (
                request["scope"] == "node" and request["target_node_id"] == node_id
            ):
                now = self.store.database_utc_now()
                if self.store.apply_cancel_request(request["request_id"], applied_at_utc=now):
                    return request
        return None

    def begin_invocation(
        self,
        node: GraphNode,
        *,
        attempt: int,
        iteration: int | None = None,
    ) -> InvocationHandle:
        iteration_path_json = json.dumps([] if iteration is None else [iteration])
        idempotent = self._is_idempotent(node)
        idempotency_key = (
            self._idempotency_key(node.id, iteration_path_json) if idempotent else None
        )
        handle = InvocationHandle(
            invocation_id=str(uuid4()),
            node_id=node.id,
            attempt=attempt,
            iteration_path_json=iteration_path_json,
            idempotent=idempotent,
            idempotency_key=idempotency_key,
        )
        if self.store is None:
            return handle
        now = self.store.database_utc_now()
        timeout = node.timeout_seconds or self.config.default_node_timeout_seconds
        self.store.create_invocation(
            {
                "invocation_id": handle.invocation_id,
                "execution_id": self.execution_id,
                "node_id": node.id,
                "iteration_path_json": iteration_path_json,
                "attempt": attempt,
                "status": "running",
                "worker_id": self.owner_id,
                "idempotent": int(idempotent),
                "idempotency_key": idempotency_key,
                "deadline_at_utc": self._plus_seconds(now, timeout),
                "lease_until_utc": self._plus_seconds(now, self.config.lease_seconds),
                "heartbeat_at_utc": now,
                "created_at_utc": now,
                "updated_at_utc": now,
            }
        )
        with self._state_lock:
            self._active_invocation_versions[handle.invocation_id] = 0
        return handle

    def accept_invocation(
        self,
        handle: InvocationHandle,
        *,
        status: str,
        error: BaseException | None = None,
    ) -> bool:
        if self.store is None:
            return True
        now = self.store.database_utc_now()
        with self._state_lock:
            version = self._active_invocation_versions.pop(handle.invocation_id, 0)
            return self.store.compare_and_set_invocation(
                handle.invocation_id,
                expected_version=version,
                from_statuses={"running"},
                to_status=status,
                updates={
                    "updated_at_utc": now,
                    "safe_error_summary": str(error) if error is not None else None,
                },
            )

    def persist_node_commit(
        self,
        handle: InvocationHandle,
        *,
        node: GraphNode,
        outputs: dict[str, Any],
        context: dict[str, Any],
    ) -> None:
        if self.store is None:
            return
        now = self.store.database_utc_now()
        safe_outputs = sanitize_for_audit(outputs)
        output_json = self._json(safe_outputs)
        self.store.save_node_output(
            {
                "output_id": str(uuid4()),
                "execution_id": self.execution_id,
                "invocation_id": handle.invocation_id,
                "node_id": node.id,
                "output_json": output_json,
                "byte_size": len(output_json.encode("utf-8")),
                "created_at_utc": now,
            }
        )
        self._snapshot_sequence += 1
        context_json = self._json(sanitize_for_audit(context))
        context_size = len(context_json.encode("utf-8"))
        if context_size > self.config.max_context_bytes:
            raise RunControlError(
                f"context exceeds byte limit: {context_size} > {self.config.max_context_bytes}"
            )
        self.store.save_context_snapshot(
            {
                "snapshot_id": str(uuid4()),
                "run_id": self.run_id,
                "execution_id": self.execution_id,
                "sequence": self._snapshot_sequence,
                "redacted_context_json": context_json,
                "byte_size": context_size,
                "created_at_utc": now,
            }
        )

    def complete(
        self,
        *,
        status: str,
        result_summary: dict[str, Any],
        trace: list[dict[str, Any]],
    ) -> None:
        if self.store is None:
            return
        self._stop_heartbeat()
        now = self.store.database_utc_now()
        self._persist_trace(trace, now)
        if not self.store.compare_and_set_execution(
            self.execution_id,
            expected_version=self._execution_version,
            from_statuses={"running"},
            to_status=status,
            updates={"finished_at_utc": now},
        ):
            raise RunControlError("execution terminal state lost CAS race")
        if not self.store.compare_and_set_run(
            self.run_id,
            expected_version=self._run_version,
            from_statuses={"running"},
            to_status=status,
            updates={
                "finished_at_utc": now,
                "result_summary_json": self._json(sanitize_for_audit(result_summary)),
                "updated_at_utc": now,
            },
        ):
            raise RunControlError("run terminal state lost CAS race")

    def fail(
        self,
        error: BaseException,
        trace: list[dict[str, Any]],
        *,
        status: str = "failed",
    ) -> None:
        if self.store is None or not self._started:
            return
        self._stop_heartbeat()
        now = self.store.database_utc_now()
        self._persist_trace(trace, now)
        self.store.compare_and_set_execution(
            self.execution_id,
            expected_version=self._execution_version,
            from_statuses={"running"},
            to_status=status,
            updates={"finished_at_utc": now},
        )
        self.store.compare_and_set_run(
            self.run_id,
            expected_version=self._run_version,
            from_statuses={"running"},
            to_status=status,
            updates={
                "finished_at_utc": now,
                "result_summary_json": self._json({"safe_error": str(error)}),
                "updated_at_utc": now,
            },
        )

    def _persist_trace(self, trace: list[dict[str, Any]], now: str) -> None:
        if self.store is None:
            return
        records: list[dict[str, Any]] = []
        total = 0
        truncated = False
        for sequence, event in enumerate(trace, start=1):
            payload_json = self._json(sanitize_for_audit(event))
            size = len(payload_json.encode("utf-8"))
            if total + size > self.config.max_trace_bytes:
                truncated = True
                break
            total += size
            records.append(
                {
                    "trace_event_id": str(uuid4()),
                    "run_id": self.run_id,
                    "execution_id": self.execution_id,
                    "sequence": sequence,
                    "node_id": event.get("node_id"),
                    "status": str(event.get("status", "unknown")),
                    "schema_version": str(event.get("schema_version", "1.0")),
                    "payload_json": payload_json,
                    "payload_truncated": 0,
                    "occurred_at_utc": str(event.get("ended_at", now)),
                }
            )
        if truncated:
            records.append(
                {
                    "trace_event_id": str(uuid4()),
                    "run_id": self.run_id,
                    "execution_id": self.execution_id,
                    "sequence": len(records) + 1,
                    "node_id": None,
                    "status": "warning",
                    "schema_version": "2.0",
                    "payload_json": self._json({"warning": "trace truncated by byte limit"}),
                    "payload_truncated": 1,
                    "occurred_at_utc": now,
                }
            )
        self.store.append_trace_batch(records)

    def _is_idempotent(self, node: GraphNode) -> bool:
        if not isinstance(node, GraphToolNode):
            return True
        spec = self.tool_registry.get_spec(node.tool)
        return bool(spec and spec.idempotent)

    def _idempotency_key(self, node_id: str, iteration_path_json: str) -> str:
        raw = f"{self.run_id}:{node_id}:{iteration_path_json}".encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    def _json(self, value: Any) -> str:
        try:
            return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        except (TypeError, ValueError) as exc:
            raise RunControlError("persisted runtime data must be JSON-compatible") from exc

    def _plus_seconds(self, value: str, seconds: float) -> str:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return (parsed + timedelta(seconds=seconds)).astimezone(timezone.utc).isoformat().replace(
            "+00:00", "Z"
        )

    def _start_heartbeat(self) -> None:
        if self.store is None:
            return
        self._heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop,
            name=f"run-heartbeat-{self.execution_id}",
            daemon=True,
        )
        self._heartbeat_thread.start()

    def _stop_heartbeat(self) -> None:
        self._heartbeat_stop.set()
        thread = self._heartbeat_thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=max(1.0, self.config.heartbeat_interval_seconds * 2))

    def _heartbeat_loop(self) -> None:
        while not self._heartbeat_stop.wait(self.config.heartbeat_interval_seconds):
            try:
                assert self.store is not None
                now = self.store.database_utc_now()
                lease = self._plus_seconds(now, self.config.lease_seconds)
                with self._state_lock:
                    won = self.store.compare_and_set_execution(
                        self.execution_id,
                        expected_version=self._execution_version,
                        from_statuses={"running"},
                        to_status="running",
                        updates={
                            "owner_id": self.owner_id,
                            "owner_lease_until_utc": lease,
                        },
                    )
                    if not won:
                        raise RunControlError("execution heartbeat lost CAS race")
                    self._execution_version += 1
                    for invocation_id, version in list(
                        self._active_invocation_versions.items()
                    ):
                        invocation_won = self.store.heartbeat(
                            invocation_id,
                            worker_id=self.owner_id,
                            expected_version=version,
                            heartbeat_at_utc=now,
                            lease_until_utc=lease,
                        )
                        if invocation_won:
                            self._active_invocation_versions[invocation_id] = version + 1
                        else:
                            # Poller 或取消请求已经赢得终态；后续业务结果会被 CAS 拒绝。
                            self._active_invocation_versions.pop(invocation_id, None)
            except BaseException as exc:
                self._heartbeat_error = exc
                self._heartbeat_stop.set()
                return
