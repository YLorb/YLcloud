from pathlib import Path

import pytest

from sandbox_service.catalog import ResourceLimits, SandboxToolDefinition, ToolCatalog
from sandbox_service.models import InvocationRequest
from sandbox_service.runtime import DockerRuntime, InMemoryRuntime
from sandbox_service.runtime import RuntimeTimeoutError
from sandbox_service.service import SandboxService


IMAGE = "registry.example/ylcloud/json-echo@sha256:" + "a" * 64


def tool() -> SandboxToolDefinition:
    return SandboxToolDefinition(
        name="data.json.echo", version="1.0.0", image=IMAGE,
        entrypoint=("/app/tool", "--request", "/sandbox/request/request.json"),
        input_schema={"type": "object", "required": ["value"], "properties": {"value": {"type": "string"}}, "additionalProperties": False},
        output_schema={"type": "object", "required": ["value"]},
    )


def request(invocation_id: str = "invocation-1", key: str = "idem-key-1", value: str = "ok") -> InvocationRequest:
    return InvocationRequest(
        invocation_id=invocation_id, idempotency_key=key, tool_name="data.json.echo",
        tool_version="1.0.0", arguments={"value": value}, subject_id="user:7",
        parent_trace={"trace_id": "a" * 32, "span_id": "b" * 16},
    )


def test_catalog_rejects_mutable_image_tag() -> None:
    with pytest.raises(ValueError, match="digest"):
        SandboxToolDefinition(name="bad", version="1", image="tool:latest", entrypoint=("/tool",),
                              input_schema={}, output_schema={})


def test_catalog_accepts_local_immutable_image_id() -> None:
    definition = SandboxToolDefinition(name="local", version="1", image="sha256:" + "b" * 64,
                                       entrypoint=("/tool",), input_schema={}, output_schema={})
    assert definition.image.startswith("sha256:")


def test_invocation_is_idempotent_across_new_invocation_id() -> None:
    runtime = InMemoryRuntime({("data.json.echo", "1.0.0"): lambda value: value})
    service = SandboxService(ToolCatalog([tool()]), runtime)
    first = service.invoke(request())
    second = service.invoke(request(invocation_id="invocation-2"))
    assert first == second
    assert runtime.calls == 1


def test_idempotency_key_rejects_different_payload() -> None:
    service = SandboxService(ToolCatalog([tool()]), InMemoryRuntime({("data.json.echo", "1.0.0"): lambda value: value}))
    service.invoke(request())
    with pytest.raises(ValueError, match="idempotency"):
        service.invoke(request(invocation_id="invocation-2", value="changed"))


def test_input_schema_is_enforced_before_runtime() -> None:
    runtime = InMemoryRuntime({("data.json.echo", "1.0.0"): lambda value: value})
    service = SandboxService(ToolCatalog([tool()]), runtime)
    with pytest.raises(ValueError, match="schema"):
        service.invoke(request(value=1))  # type: ignore[arg-type]
    assert runtime.calls == 0


def test_docker_command_has_required_security_controls(tmp_path: Path) -> None:
    (tmp_path / "request").mkdir()
    (tmp_path / "output").mkdir()
    command = DockerRuntime().build_command("invocation-1", tool(), tmp_path)
    joined = " ".join(command)
    assert command[:2] == ["docker", "run"]
    assert "--network none" in joined
    assert "--read-only" in command
    assert "--cap-drop ALL" in joined
    assert "no-new-privileges=true" in command
    assert "--pids-limit 64" in joined
    assert "--memory-swap 536870912" in joined
    assert "--entrypoint /app/tool" in joined
    assert "sh" not in command and "bash" not in command


def test_docker_command_can_use_shared_work_volume(tmp_path: Path) -> None:
    command = DockerRuntime(work_root=str(tmp_path.parent), work_volume="ylcloud-sandbox-work").build_command(
        "invocation-1", tool(), tmp_path
    )
    joined = " ".join(command)
    assert "type=volume,src=ylcloud-sandbox-work,dst=/sandbox" in joined
    assert f"volume-subpath={tmp_path.name}" in joined
    assert "type=bind" not in joined


def test_timeout_has_stable_safe_status() -> None:
    class TimeoutRuntime(InMemoryRuntime):
        def execute(self, invocation_id, definition, arguments, timeout):
            raise RuntimeTimeoutError("internal detail")

    service = SandboxService(ToolCatalog([tool()]), TimeoutRuntime({}))
    response = service.invoke(request())
    assert response.status.value == "TIMED_OUT"
    assert response.error_code == "SANDBOX_TIMED_OUT"
    assert "internal detail" not in response.error_message
