from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from mini_agent_flow.contracts.models import (
    WorkflowResult,
    WorkflowRunAccepted,
    WorkflowRunCreateRequest,
)


class RunServiceError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code
        self.retryable = retryable


@dataclass(frozen=True, slots=True)
class RequestMetadata:
    idempotency_key: str
    request_id: str
    traceparent: str | None


class RunApplicationService(ABC):
    """API 与持久化/调度实现之间的边界，TASK-003 将接入 MySQL 实现。"""

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    @abstractmethod
    async def is_ready(self) -> bool:
        raise NotImplementedError

    @abstractmethod
    async def create_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        raise NotImplementedError

    @abstractmethod
    async def get_run(self, run_id: str) -> dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def get_result(self, run_id: str) -> WorkflowResult:
        raise NotImplementedError

    @abstractmethod
    async def cancel_run(self, run_id: str) -> dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        raise NotImplementedError


class UnconfiguredRunApplicationService(RunApplicationService):
    """持久化尚未配置时 fail-closed，健康存活但永不宣告就绪。"""

    async def is_ready(self) -> bool:
        return False

    async def create_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        raise self._unavailable()

    async def get_run(self, run_id: str) -> dict[str, Any]:
        raise self._unavailable()

    async def get_result(self, run_id: str) -> WorkflowResult:
        raise self._unavailable()

    async def cancel_run(self, run_id: str) -> dict[str, Any]:
        raise self._unavailable()

    async def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        raise self._unavailable()

    @staticmethod
    def _unavailable() -> RunServiceError:
        return RunServiceError(
            "SERVICE_NOT_READY",
            "durable workflow store is not configured",
            status_code=503,
            retryable=True,
        )
