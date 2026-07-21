from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable
from uuid import uuid4

from mini_agent_flow.contracts.models import (
    WorkflowResult,
    WorkflowRunAccepted,
    WorkflowRunCreateRequest,
)
from mini_agent_flow.persistence.mysql_store import MySQLWorkflowStore
from mini_agent_flow.service.run_service import (
    RequestMetadata,
    RunApplicationService,
    RunServiceError,
)

RunDispatcher = Callable[[dict[str, Any]], Awaitable[None]]


class MySQLRunApplicationService(RunApplicationService):
    """Async HTTP boundary over the blocking PyMySQL adapter.

    Returning from ``create_run`` means the run, initial execution and
    idempotency record have all committed in one MySQL transaction.
    """

    def __init__(
        self,
        store: MySQLWorkflowStore,
        *,
        dispatcher: RunDispatcher | None = None,
        worker_id: str | None = None,
        poll_interval_seconds: float = 1.0,
        lease_seconds: int = 30,
    ) -> None:
        if poll_interval_seconds <= 0 or lease_seconds < 1:
            raise ValueError("poll interval and lease must be positive")
        self.store = store
        self.dispatcher = dispatcher
        self.worker_id = worker_id or f"workflow-{uuid4()}"
        self.poll_interval_seconds = poll_interval_seconds
        self.lease_seconds = lease_seconds
        self._poller: asyncio.Task[None] | None = None

    async def start(self) -> None:
        await asyncio.to_thread(self.store.migrate)
        if self.dispatcher is not None:
            self._poller = asyncio.create_task(self._recovery_loop())

    async def stop(self) -> None:
        if self._poller is None:
            return
        self._poller.cancel()
        try:
            await self._poller
        except asyncio.CancelledError:
            pass
        self._poller = None

    async def is_ready(self) -> bool:
        return await asyncio.to_thread(self.store.ping)

    async def create_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        return await asyncio.to_thread(self.store.accept_run, request, metadata)

    async def get_run(self, run_id: str) -> dict[str, Any]:
        row = await asyncio.to_thread(self.store.get_run, run_id)
        if row is None:
            raise self._not_found()
        # Never expose stored question/context through the operational status API.
        return {
            "contractVersion": "1.0",
            "runId": row["run_id"],
            "executionId": row["current_execution_id"],
            "executionEpoch": row["execution_epoch"],
            "status": row["status"],
            "createdAt": _aware(row["created_at"]),
            "updatedAt": _aware(row["updated_at"]),
        }

    async def get_result(self, run_id: str) -> WorkflowResult:
        payload = await asyncio.to_thread(self.store.get_result, run_id)
        if payload is None:
            if await asyncio.to_thread(self.store.get_run, run_id) is None:
                raise self._not_found()
            raise RunServiceError(
                "RESULT_NOT_READY", "workflow result is not ready", status_code=409, retryable=True
            )
        return WorkflowResult.model_validate(payload)

    async def cancel_run(self, run_id: str) -> dict[str, Any]:
        if await asyncio.to_thread(self.store.cancel_run, run_id):
            return {"contractVersion": "1.0", "runId": run_id, "status": "CANCELLED"}
        row = await asyncio.to_thread(self.store.get_run, run_id)
        if row is None:
            raise self._not_found()
        return {"contractVersion": "1.0", "runId": run_id, "status": row["status"]}

    async def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        return await asyncio.to_thread(self.store.retry_run, run_id)

    async def recover_once(self) -> bool:
        if self.dispatcher is None:
            return False
        row = await asyncio.to_thread(
            self.store.claim_recoverable_run, self.worker_id, self.lease_seconds
        )
        if row is None:
            return False
        await self.dispatcher(row)
        return True

    async def _recovery_loop(self) -> None:
        while True:
            try:
                claimed = await self.recover_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                # The expired lease makes the run recoverable by this or another replica.
                claimed = False
            if not claimed:
                await asyncio.sleep(self.poll_interval_seconds)

    @staticmethod
    def _not_found() -> RunServiceError:
        return RunServiceError("NOT_FOUND", "workflow run not found", status_code=404)


def _aware(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()
