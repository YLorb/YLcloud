from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pytest

from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_executor import GraphWorkflowExecutor
from mini_agent_flow.engine.graph_models import GraphWorkflow
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.registry import ToolRegistry
from mini_agent_flow.tools.sandbox_client import SandboxInvocationResult, SandboxTimeoutError
from mini_agent_flow.tools.spec import SandboxToolDescriptor, ToolSpec


@dataclass
class FakeSandboxClient:
    calls: int = 0

    def invoke(self, spec: ToolSpec, arguments: dict[str, Any], **kwargs: Any) -> SandboxInvocationResult:
        self.calls += 1
        return SandboxInvocationResult({"value": arguments["value"]}, {"span_id": "child", "secret": "hidden"})


def workflow() -> GraphWorkflow:
    return GraphWorkflow.model_validate({
        "version": "2.0", "name": "sandbox_graph", "outputs": ["result"],
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "call", "type": "tool", "tool": "data_echo", "input": {"value": "ok"}, "output": "result", "publish": True},
            {"id": "end", "type": "end"},
        ],
        "edges": [{"from": "start", "to": "call"}, {"from": "call", "to": "end"}],
    })


def registry() -> ToolRegistry:
    value = ToolRegistry()
    value.register("data_echo", lambda _: (_ for _ in ()).throw(AssertionError("local fallback")), spec=ToolSpec(
        name="data_echo", execution_mode="sandbox", sandbox=SandboxToolDescriptor("1.0.0"),
        input_schema={"type": "object"}, output_schema={"type": "object"}, idempotent=True,
    ))
    return value


def test_sandbox_tool_uses_remote_client_and_records_child_span() -> None:
    client = FakeSandboxClient()
    result = GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry(), sandbox_client=client).run(workflow())
    assert result.final_output == {"value": "ok"}
    assert client.calls == 1
    event = next(event for event in result.trace if event["node_id"] == "call")
    assert event["metadata"]["sandbox_span"]["span_id"] == "child"
    assert event["metadata"]["sandbox_span"]["secret"] == "[REDACTED]"


def test_sandbox_tool_never_falls_back_when_client_missing() -> None:
    with pytest.raises(WorkflowExecutionError, match="sandbox client is unavailable"):
        GraphWorkflowExecutor(llm=MockLLM(), tool_registry=registry(), sandbox_client=None).run(workflow())


def test_sandbox_spec_requires_version_descriptor() -> None:
    with pytest.raises(ValueError, match="descriptor"):
        ToolSpec(name="bad", execution_mode="sandbox")


def test_sandbox_failure_preserves_child_span_metadata() -> None:
    class TimeoutClient:
        def invoke(self, *args, **kwargs):
            raise SandboxTimeoutError(
                "deadline",
                span={"span_id": "failed-child", "status": "TIMED_OUT"},
            )

    with pytest.raises(WorkflowExecutionError) as captured:
        GraphWorkflowExecutor(
            llm=MockLLM(), tool_registry=registry(), sandbox_client=TimeoutClient()
        ).run(workflow())
    metadata = getattr(captured.value, "safe_metadata")
    assert metadata["sandbox_span"]["span_id"] == "failed-child"
