from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any
from uuid import UUID

from fastapi.testclient import TestClient

from mini_agent_flow.contracts.models import (
    WorkflowResult,
    WorkflowRunAccepted,
    WorkflowRunCreateRequest,
)
from mini_agent_flow.service.api import RequestSizeLimitMiddleware, create_app
from mini_agent_flow.service.run_service import RequestMetadata, RunApplicationService
from mini_agent_flow.service.settings import ServiceSettings
from mini_agent_flow.security.service_jwt import AllowAllServiceAuthenticator


RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
EXECUTION_ID = UUID("22222222-2222-4222-8222-222222222222")


def _test_app(service=None, settings=None):
    return create_app(service, settings, AllowAllServiceAuthenticator())


class FakeRunService(RunApplicationService):
    def __init__(self, *, ready: bool = True, delay: float = 0) -> None:
        self.ready = ready
        self.delay = delay
        self.started = False
        self.stopped = False
        self.metadata: RequestMetadata | None = None

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def is_ready(self) -> bool:
        return self.ready

    async def create_run(
        self, request: WorkflowRunCreateRequest, metadata: RequestMetadata
    ) -> WorkflowRunAccepted:
        if self.delay:
            await asyncio.sleep(self.delay)
        self.metadata = metadata
        return self._accepted()

    async def get_run(self, run_id: str) -> dict[str, Any]:
        return {"runId": run_id, "status": "RUNNING"}

    async def get_result(self, run_id: str) -> WorkflowResult:
        raise AssertionError("result is not used by this test")

    async def cancel_run(self, run_id: str) -> dict[str, Any]:
        return {"runId": run_id, "status": "CANCELLED"}

    async def retry_run(self, run_id: str) -> WorkflowRunAccepted:
        return self._accepted(epoch=2)

    @staticmethod
    def _accepted(epoch: int = 1) -> WorkflowRunAccepted:
        return WorkflowRunAccepted(
            contract_version="1.0",
            run_id=RUN_ID,
            execution_id=EXECUTION_ID,
            execution_epoch=epoch,
            status="QUEUED",
            accepted_at=datetime.now(timezone.utc),
        )


def _request() -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "workflowType": "ASSISTANT",
        "workflowVersion": "2.0",
        "userId": 101,
        "sessionId": 201,
        "sourceMessageId": 301,
        "assistantMessageId": 302,
        "question": "测试服务入口",
        "shortTermContext": [],
        "permissionScope": {},
        "configVersion": "v1",
        "requestContextHash": "a" * 64,
        "budget": {
            "maxBusinessNodes": 2,
            "maxParallelNodes": 1,
            "maxModelCalls": 2,
            "maxRuntimeMs": 5000,
        },
    }


def _headers() -> dict[str, str]:
    return {
        "Idempotency-Key": "assistant-302",
        "X-Request-Id": "request-1",
        "traceparent": "00-11111111111111111111111111111111-2222222222222222-01",
    }


def test_health_ready_lifespan_and_run_routes() -> None:
    service = FakeRunService()
    with TestClient(_test_app(service)) as client:
        assert service.started is True
        assert client.get("/health").json() == {"status": "UP"}
        assert client.get("/ready").json() == {"status": "READY"}

        created = client.post(
            "/internal/v1/workflow-runs", json=_request(), headers=_headers()
        )
        assert created.status_code == 202
        assert created.json()["runId"] == str(RUN_ID)
        assert service.metadata == RequestMetadata(
            idempotency_key="assistant-302",
            request_id="request-1",
            traceparent="00-11111111111111111111111111111111-2222222222222222-01",
        )
        assert client.get(f"/internal/v1/workflow-runs/{RUN_ID}").status_code == 200
        assert client.post(f"/internal/v1/workflow-runs/{RUN_ID}/cancel").json()["status"] == "CANCELLED"
        assert client.post(f"/internal/v1/workflow-runs/{RUN_ID}/retry").json()["executionEpoch"] == 2
    assert service.stopped is True


def test_unconfigured_service_is_alive_but_not_ready() -> None:
    with TestClient(create_app()) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/ready").status_code == 503
        response = client.post(
            "/internal/v1/workflow-runs", json=_request(), headers=_headers()
        )
        assert response.status_code == 503
        assert response.json()["code"] == "SERVICE_AUTH_NOT_CONFIGURED"


def test_contract_body_limit_and_timeout_are_enforced() -> None:
    settings = ServiceSettings(
        max_request_bytes=64,
        request_timeout_seconds=0.01,
        max_concurrent_requests=1,
    )
    with TestClient(_test_app(FakeRunService(delay=0.05), settings)) as client:
        oversized = client.post(
            "/internal/v1/workflow-runs", json=_request(), headers=_headers()
        )
        assert oversized.status_code == 413

    timeout_settings = ServiceSettings(
        max_request_bytes=1_048_576,
        request_timeout_seconds=0.01,
        max_concurrent_requests=1,
    )
    with TestClient(_test_app(FakeRunService(delay=0.05), timeout_settings)) as client:
        timed_out = client.post(
            "/internal/v1/workflow-runs", json=_request(), headers=_headers()
        )
        assert timed_out.status_code == 504
        assert timed_out.json()["code"] == "REQUEST_TIMED_OUT"


def test_invalid_contract_and_missing_headers_use_stable_error_envelope() -> None:
    invalid = _request()
    invalid["contractVersion"] = "2.0"
    with TestClient(_test_app(FakeRunService())) as client:
        response = client.post("/internal/v1/workflow-runs", json=invalid)
        assert response.status_code == 422
        assert response.json()["code"] == "INVALID_REQUEST"
        assert response.json()["retryable"] is False
        serialized = response.text
        assert "测试服务入口" not in serialized
        assert client.get("/internal/v1/workflow-runs/not-a-uuid").status_code == 422


def test_chunked_body_limit_is_enforced_before_application_receives_all_data() -> None:
    received_chunks = 0
    sent: list[dict[str, Any]] = []

    async def downstream(scope, receive, send) -> None:
        nonlocal received_chunks
        while True:
            message = await receive()
            received_chunks += 1
            if not message.get("more_body", False):
                break
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    chunks = iter(
        [
            {"type": "http.request", "body": b"a" * 40, "more_body": True},
            {"type": "http.request", "body": b"b" * 40, "more_body": False},
        ]
    )

    async def receive() -> dict[str, Any]:
        return next(chunks)

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    asyncio.run(
        RequestSizeLimitMiddleware(downstream, max_bytes=64)(
            {
                "type": "http",
                "method": "POST",
                "path": "/internal/v1/workflow-runs",
                "headers": [],
            },
            receive,
            send,
        )
    )

    assert received_chunks == 1
    assert sent[0]["status"] == 413


def test_unexpected_error_is_sanitized() -> None:
    class BrokenRunService(FakeRunService):
        async def create_run(self, request, metadata):
            raise RuntimeError("database password=must-not-leak")

    with TestClient(_test_app(BrokenRunService()), raise_server_exceptions=False) as client:
        response = client.post(
            "/internal/v1/workflow-runs", json=_request(), headers=_headers()
        )
        assert response.status_code == 500
        assert response.json()["code"] == "INTERNAL_ERROR"
        assert "password" not in response.text
