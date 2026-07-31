from __future__ import annotations

import time
from dataclasses import replace

import pytest

from mini_agent_flow.tools.spec import (
    ImportableToolEntrypoint,
    ProcessToolProviderDescriptor,
    ToolSpec,
)
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.secrets import MappingSecretProvider
from mini_agent_flow.tools.worker_test_tools import isolated_echo, isolated_sleep
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.workers.isolated_process import (
    IsolatedProcessRunner,
    IsolatedToolError,
    IsolatedToolTimeoutError,
)
from mini_agent_flow.workers.protocol import WorkerProtocolError, encode_message


def isolated_spec(function: str) -> ToolSpec:
    return ToolSpec(
        name=function,
        execution_mode="isolated_process",
        loader=ImportableToolEntrypoint(
            module="mini_agent_flow.tools.worker_test_tools",
            function=function,
        ),
        idempotent=True,
    )


def test_isolated_worker_returns_json_result() -> None:
    runner = IsolatedProcessRunner(cancel_grace_seconds=0.1)

    result = runner.invoke(
        isolated_spec("isolated_echo"),
        {"value": [1, 2, 3]},
        timeout_seconds=3,
    )

    assert result == {"value": [1, 2, 3]}


def test_isolated_worker_rebuilds_process_tool_provider() -> None:
    runner = IsolatedProcessRunner(cancel_grace_seconds=0.1)
    spec = ToolSpec(
        name="provider_echo",
        execution_mode="isolated_process",
        loader=ProcessToolProviderDescriptor(
            provider_type="mini_agent_flow.tools.worker_test_tools:provider_tool_factory",
            provider_config_ref="tenant-a",
            tool_name="prefixed_echo",
        ),
        idempotent=True,
    )

    assert runner.invoke(spec, 7, timeout_seconds=3) == {
        "config_ref": "tenant-a",
        "value": 7,
    }


def test_isolated_worker_returns_safe_error() -> None:
    runner = IsolatedProcessRunner(cancel_grace_seconds=0.1)

    with pytest.raises(IsolatedToolError, match="worker failure"):
        runner.invoke(
            isolated_spec("isolated_error"),
            "boom",
            timeout_seconds=3,
        )


def test_isolated_worker_timeout_terminates_process() -> None:
    runner = IsolatedProcessRunner(cancel_grace_seconds=0.05)
    started = time.perf_counter()

    with pytest.raises(IsolatedToolTimeoutError, match="exceeded timeout"):
        runner.invoke(
            isolated_spec("isolated_sleep"),
            10,
            timeout_seconds=0.2,
        )

    assert time.perf_counter() - started < 3


def test_protocol_rejects_non_json_and_oversized_messages() -> None:
    with pytest.raises(WorkerProtocolError, match="JSON-compatible"):
        encode_message(
            {"protocol_version": 1, "invocation_id": "x", "bad": object()},
            max_bytes=100,
        )
    with pytest.raises(WorkerProtocolError, match="byte limit"):
        encode_message(
            {"protocol_version": 1, "invocation_id": "x", "value": "x" * 100},
            max_bytes=10,
        )


def test_graph_tool_handler_uses_isolated_runner() -> None:
    registry = ToolRegistry()
    registry.register(
        "isolated_echo",
        isolated_echo,
        spec=isolated_spec("isolated_echo"),
    )
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "isolated_graph",
            "outputs": ["value"],
            "nodes": [
                {"id": "start", "type": "start"},
                {"id": "call", "type": "tool", "tool": "isolated_echo", "input": {"ok": True}, "output": "value", "publish": True},
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )

    result = GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry).run(workflow)

    assert result.final_output == {"ok": True}


def test_graph_isolated_tool_receives_only_declared_secrets() -> None:
    registry = ToolRegistry()
    spec = replace(
        isolated_spec("isolated_secret_names"),
        required_secret_names=("API_KEY",),
    )
    registry.register("isolated_secret_names", isolated_echo, spec=spec)
    workflow = GraphWorkflow.model_validate(
        {
            "version": "2.0",
            "name": "isolated_secret",
            "outputs": ["result"],
            "nodes": [
                {"id": "start", "type": "start"},
                {
                    "id": "call",
                    "type": "tool",
                    "tool": "isolated_secret_names",
                    "input": "hello",
                    "output": "result",
                    "publish": True,
                    "timeout_seconds": 2,
                },
                {"id": "end", "type": "end"},
            ],
            "edges": [
                {"from": "start", "to": "call"},
                {"from": "call", "to": "end"},
            ],
        }
    )

    result = GraphWorkflowExecutor(
        MockLLM(),
        registry,
        secret_provider=MappingSecretProvider(
            {"API_KEY": "hidden", "UNDECLARED": "must-not-cross"}
        ),
    ).run(workflow)

    assert result.final_output == {"value": "hello", "secret_names": ["API_KEY"]}
    assert "hidden" not in str(result.trace)


def test_isolated_worker_redacts_secret_from_error() -> None:
    runner = IsolatedProcessRunner(cancel_grace_seconds=0.1)
    spec = replace(
        isolated_spec("isolated_secret_error"),
        required_secret_names=("API_KEY",),
    )

    with pytest.raises(IsolatedToolError) as exc_info:
        runner.invoke(
            spec,
            "boom",
            timeout_seconds=3,
            secrets={"API_KEY": "do-not-leak"},
        )

    assert "do-not-leak" not in str(exc_info.value)
    assert "[REDACTED]" in str(exc_info.value)
