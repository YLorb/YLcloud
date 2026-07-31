from __future__ import annotations

import json
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable
from uuid import uuid4

from mini_agent_flow.engine.executor import WorkflowRunResult
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.poller import DeadlinePoller
from mini_agent_flow.engine.runtime_config import RuntimeConfig
from mini_agent_flow.persistence.cleanup import CleanupJob, CleanupResult
from mini_agent_flow.persistence.sqlite_store import SQLiteRunControlStore


class RunManagerError(RuntimeError):
    pass


@dataclass(frozen=True)
class RecoveryResult:
    scanned: int
    restarted: int
    abandoned: int
    stale: int


ExecutorFactory = Callable[[], GraphWorkflowExecutor]


class RunManager:
    """限制并发 Run，并集中提供恢复、取消和查询服务。"""

    def __init__(
        self,
        *,
        executor_factory: ExecutorFactory,
        store: SQLiteRunControlStore,
        config: RuntimeConfig,
        start_services: bool = False,
    ) -> None:
        self.executor_factory = executor_factory
        self.store = store
        self.config = config
        self._slots = threading.BoundedSemaphore(config.max_concurrent_runs)
        self._pool = ThreadPoolExecutor(
            max_workers=config.max_concurrent_runs,
            thread_name_prefix="workflow-run",
        )
        self._service_stop = threading.Event()
        self._service_thread: threading.Thread | None = None
        self.service_error: BaseException | None = None
        if start_services:
            self.start_services()

    def run(
        self,
        workflow: Workflow | GraphWorkflow,
        *,
        run_id: str | None = None,
        epoch: int = 1,
        restart_existing: bool = False,
    ) -> WorkflowRunResult:
        self._slots.acquire()
        try:
            executor = self.executor_factory()
            return executor.run(
                workflow,
                run_id=run_id,
                epoch=epoch,
                restart_existing=restart_existing,
            )
        finally:
            self._slots.release()

    def submit(self, workflow: Workflow | GraphWorkflow) -> Future[WorkflowRunResult]:
        return self._pool.submit(self.run, workflow)

    def request_cancel(
        self,
        run_id: str,
        *,
        target_node_id: str | None = None,
        reason: str = "USER_CANCELLED",
    ) -> str:
        run = self._require_run(run_id)
        execution_id = run.get("current_execution_id")
        if not execution_id or run["status"] not in {"pending", "running"}:
            raise RunManagerError("only pending or running runs can be cancelled")
        request_id = str(uuid4())
        created = self.store.request_cancel(
            {
                "request_id": request_id,
                "run_id": run_id,
                "execution_id": execution_id,
                "scope": "node" if target_node_id else "run",
                "target_node_id": target_node_id or "",
                "status": "pending",
                "reason": reason,
                "requested_at_utc": self.store.database_utc_now(),
            }
        )
        if not created:
            raise RunManagerError("an equivalent cancellation request is already pending")
        return request_id

    def retry(self, run_id: str) -> WorkflowRunResult:
        run = self._require_run(run_id)
        if run["status"] not in {"failed", "timed_out", "cancelled", "abandoned"}:
            raise RunManagerError("only terminal unsuccessful runs can be retried")
        workflow = self._workflow_from_run(run)
        executions = self.store.list_executions(run_id)
        epoch = max((int(item["epoch"]) for item in executions), default=0) + 1
        return self.run(
            workflow,
            run_id=run_id,
            epoch=epoch,
            restart_existing=True,
        )

    def recover_expired(self) -> RecoveryResult:
        now = self.store.database_utc_now()
        rows = self.store.list_recoverable_executions(now)
        restarted = abandoned = stale = 0
        for execution in rows:
            safe_to_restart = not self.store.has_non_idempotent_success(
                execution["execution_id"]
            )
            won = self.store.compare_and_set_execution(
                execution["execution_id"],
                expected_version=int(execution["version"]),
                from_statuses={"running"},
                to_status="abandoned",
                updates={
                    "finished_at_utc": now,
                    "safe_to_restart": int(safe_to_restart),
                },
            )
            if not won:
                stale += 1
                continue
            run = self._require_run(execution["run_id"])
            if str(run["deadline_at_utc"]) <= now:
                self.store.compare_and_set_run(
                    run["run_id"],
                    expected_version=int(run["version"]),
                    from_statuses={"running"},
                    to_status="timed_out",
                    updates={"finished_at_utc": now, "updated_at_utc": now},
                )
                abandoned += 1
                continue
            if not safe_to_restart:
                self.store.compare_and_set_run(
                    run["run_id"],
                    expected_version=int(run["version"]),
                    from_statuses={"running"},
                    to_status="abandoned",
                    updates={"finished_at_utc": now, "updated_at_utc": now},
                )
                abandoned += 1
                continue
            workflow = self._workflow_from_run(run)
            try:
                self.run(
                    workflow,
                    run_id=run["run_id"],
                    epoch=int(execution["epoch"]) + 1,
                    restart_existing=True,
                )
                restarted += 1
            except Exception:
                # 新 epoch 已由 RunController 可靠记录失败；继续扫描其他逻辑 Run。
                abandoned += 1
        return RecoveryResult(
            scanned=len(rows), restarted=restarted, abandoned=abandoned, stale=stale
        )

    def list_runs(self, *, limit: int = 100) -> list[dict[str, object]]:
        return self.store.list_runs(limit=limit)

    def show_run(self, run_id: str) -> dict[str, object]:
        run = self._require_run(run_id)
        executions = self.store.list_executions(run_id)
        details: list[dict[str, object]] = []
        for execution in executions:
            details.append(
                {
                    **execution,
                    "invocations": self.store.list_invocations(execution["execution_id"]),
                    "trace": self.store.list_trace_events(execution["execution_id"]),
                }
            )
        return {"run": run, "executions": details}

    def cleanup(self, *, dry_run: bool = True, batch_size: int = 100) -> CleanupResult:
        return CleanupJob(
            self.store,
            success_retention_days=self.config.success_retention_days,
            failure_retention_days=self.config.failure_retention_days,
            batch_size=batch_size,
        ).run(dry_run=dry_run)

    def shutdown(self, *, wait: bool = True) -> None:
        self._service_stop.set()
        if self._service_thread is not None:
            self._service_thread.join(timeout=max(1.0, self.config.poll_interval_seconds * 3))
        self._pool.shutdown(wait=wait, cancel_futures=False)

    def start_services(self) -> None:
        if self._service_thread is not None:
            return
        # 启动时先处理遗留 execution，再开始周期 Deadline 扫描。
        self.recover_expired()
        self._service_thread = threading.Thread(
            target=self._control_loop,
            name="workflow-control-loop",
            daemon=True,
        )
        self._service_thread.start()

    def _control_loop(self) -> None:
        poller = DeadlinePoller(
            self.store,
            owner_id=f"run-manager-{uuid4()}",
            lease_seconds=self.config.lease_seconds,
        )
        while not self._service_stop.is_set():
            try:
                poller.tick()
            except BaseException as exc:
                self.service_error = exc
                self._service_stop.set()
                return
            self._service_stop.wait(self.config.poll_interval_seconds)

    def _require_run(self, run_id: str) -> dict[str, object]:
        run = self.store.get_run(run_id)
        if run is None:
            raise RunManagerError(f"run does not exist: {run_id}")
        return run

    def _workflow_from_run(self, run: dict[str, object]) -> GraphWorkflow:
        raw = run.get("workflow_json")
        if not isinstance(raw, str) or not raw:
            raise RunManagerError("run has no recoverable workflow snapshot")
        try:
            return GraphWorkflow.model_validate(json.loads(raw))
        except (ValueError, TypeError) as exc:
            raise RunManagerError("persisted workflow snapshot is invalid") from exc
