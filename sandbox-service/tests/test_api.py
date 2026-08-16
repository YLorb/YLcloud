from fastapi.testclient import TestClient

from sandbox_service.api import create_app
from sandbox_service.catalog import SandboxToolDefinition, ToolCatalog
from sandbox_service.runtime import InMemoryRuntime
from sandbox_service.service import SandboxService


def client(monkeypatch) -> TestClient:
    monkeypatch.setenv("SANDBOX_ENABLED", "true")
    monkeypatch.setenv("SANDBOX_SERVICE_TOKEN", "x" * 32)
    tool = SandboxToolDefinition(
        name="data.echo", version="1", image="example/tool@sha256:" + "a" * 64,
        entrypoint=("/tool",), input_schema={"type": "object"}, output_schema={"type": "object"},
    )
    service = SandboxService(ToolCatalog([tool]), InMemoryRuntime({("data.echo", "1"): lambda value: value}))
    return TestClient(create_app(service))


def payload() -> dict:
    return {"invocation_id": "invocation-1", "idempotency_key": "idempotency-1",
            "tool_name": "data.echo", "tool_version": "1", "arguments": {"value": "ok"},
            "parent_trace": {"trace_id": "a" * 32, "span_id": "b" * 16}, "subject_id": "user:7"}


def test_internal_api_requires_service_token(monkeypatch) -> None:
    response = client(monkeypatch).post("/internal/v1/invocations", json=payload())
    assert response.status_code == 401


def test_user_history_is_scoped_by_subject(monkeypatch) -> None:
    api = client(monkeypatch)
    headers = {"Authorization": "Bearer " + "x" * 32}
    assert api.post("/internal/v1/invocations", json=payload(), headers=headers).status_code == 200
    assert len(api.get("/internal/v1/invocations?subject_id=user:7", headers=headers).json()) == 1
    assert api.get("/internal/v1/invocations?subject_id=user:8", headers=headers).json() == []
