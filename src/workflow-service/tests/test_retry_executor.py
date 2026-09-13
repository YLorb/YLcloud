from __future__ import annotations

from typing import Any

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.tools.registry import ToolRegistry


class StaticLLM:
    """固定返回文本的测试 LLM。"""

    def generate(self, prompt: str) -> str:
        """返回可预测结果。"""

        return f"answer: {prompt}"


class FlakyLLM:
    """第一次失败、第二次成功的测试 LLM。"""

    def __init__(self) -> None:
        self.calls = 0

    def generate(self, prompt: str) -> str:
        """模拟临时失败。"""

        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("temporary llm failure")
        return f"retry ok: {prompt}"


class FlakyTool:
    """前几次失败，之后成功的测试工具。"""

    def __init__(self, failures_before_success: int = 1) -> None:
        self.calls = 0
        self.failures_before_success = failures_before_success

    def __call__(self, input_value: Any) -> str:
        """模拟临时工具失败。"""

        self.calls += 1
        if self.calls <= self.failures_before_success:
            raise RuntimeError("temporary tool failure")
        return f"tool ok: {input_value}"


def load_workflow(data: dict[str, Any], registry: ToolRegistry):
    """通过 Loader + Validator 加载测试 workflow。"""

    return JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load_data(data)


def test_tool_node_retries_and_succeeds() -> None:
    """tool 节点第一次失败、第二次成功时，workflow 应继续执行。"""

    flaky_tool = FlakyTool(failures_before_success=1)
    registry = ToolRegistry()
    registry.register("flaky_tool", flaky_tool)
    workflow = load_workflow(
        {
            "version": "1.0",
            "name": "tool_retry_success",
            "inputs": {"message": "hello"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_tool"},
                {
                    "id": "call_tool",
                    "type": "tool",
                    "tool": "flaky_tool",
                    "input": "{{ message }}",
                    "output": "tool_result",
                    "next": "end",
                    "retry": {"max_attempts": 2, "backoff_seconds": 0},
                },
                {"id": "end", "type": "end"},
            ],
        },
        registry,
    )
    executor = SequentialWorkflowExecutor(llm=StaticLLM(), tool_registry=registry)

    result = executor.run(workflow)
    tool_events = [event for event in result.trace if event["node_id"] == "call_tool"]

    assert flaky_tool.calls == 2
    assert result.context["tool_result"] == "tool ok: hello"
    assert [event["status"] for event in tool_events] == ["failed", "success"]
    assert [event["attempt"] for event in tool_events] == [1, 2]


def test_tool_node_fails_after_max_attempts() -> None:
    """tool 节点超过 max_attempts 后应失败，并保留全部 retry trace。"""

    failing_tool = FlakyTool(failures_before_success=10)
    registry = ToolRegistry()
    registry.register("failing_tool", failing_tool)
    workflow = load_workflow(
        {
            "version": "1.0",
            "name": "tool_retry_failure",
            "inputs": {"message": "hello"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_tool"},
                {
                    "id": "call_tool",
                    "type": "tool",
                    "tool": "failing_tool",
                    "input": "{{ message }}",
                    "output": "tool_result",
                    "next": "end",
                    "retry": {"max_attempts": 3, "backoff_seconds": 0},
                },
                {"id": "end", "type": "end"},
            ],
        },
        registry,
    )
    executor = SequentialWorkflowExecutor(llm=StaticLLM(), tool_registry=registry)

    with pytest.raises(WorkflowExecutionError) as exc_info:
        executor.run(workflow)

    tool_events = [event for event in exc_info.value.trace if event["node_id"] == "call_tool"]
    assert failing_tool.calls == 3
    assert [event["status"] for event in tool_events] == ["failed", "failed", "failed"]
    assert [event["attempt"] for event in tool_events] == [1, 2, 3]
    assert all("tool_result" not in event["context_after_keys"] for event in tool_events)


def test_node_without_retry_uses_single_attempt() -> None:
    """没有配置 retry 时，节点失败后不应重试。"""

    flaky_tool = FlakyTool(failures_before_success=1)
    registry = ToolRegistry()
    registry.register("flaky_tool", flaky_tool)
    workflow = load_workflow(
        {
            "version": "1.0",
            "name": "tool_no_retry",
            "inputs": {"message": "hello"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_tool"},
                {
                    "id": "call_tool",
                    "type": "tool",
                    "tool": "flaky_tool",
                    "input": "{{ message }}",
                    "output": "tool_result",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        },
        registry,
    )
    executor = SequentialWorkflowExecutor(llm=StaticLLM(), tool_registry=registry)

    with pytest.raises(WorkflowExecutionError) as exc_info:
        executor.run(workflow)

    tool_events = [event for event in exc_info.value.trace if event["node_id"] == "call_tool"]
    assert flaky_tool.calls == 1
    assert len(tool_events) == 1
    assert tool_events[0]["attempt"] == 1


def test_llm_node_retries_and_succeeds() -> None:
    """LLM 节点也应支持 retry。"""

    registry = ToolRegistry()
    workflow = load_workflow(
        {
            "version": "1.0",
            "name": "llm_retry_success",
            "inputs": {"goal": "demo"},
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "目标：{{ goal }}",
                    "output": "answer",
                    "next": "end",
                    "retry": {"max_attempts": 2, "backoff_seconds": 0},
                },
                {"id": "end", "type": "end"},
            ],
        },
        registry,
    )
    llm = FlakyLLM()
    executor = SequentialWorkflowExecutor(llm=llm, tool_registry=registry)

    result = executor.run(workflow)
    llm_events = [event for event in result.trace if event["node_id"] == "plan"]

    assert llm.calls == 2
    assert result.context["answer"] == "retry ok: 目标：demo"
    assert [event["status"] for event in llm_events] == ["failed", "success"]
    assert [event["attempt"] for event in llm_events] == [1, 2]


def test_retry_failure_does_not_write_output_key() -> None:
    """所有 retry 都失败时，不应提前写入节点 output key。"""

    failing_tool = FlakyTool(failures_before_success=10)
    registry = ToolRegistry()
    registry.register("failing_tool", failing_tool)
    workflow = load_workflow(
        {
            "version": "1.0",
            "name": "retry_no_partial_context_write",
            "inputs": {"message": "hello"},
            "nodes": [
                {"id": "start", "type": "start", "next": "call_tool"},
                {
                    "id": "call_tool",
                    "type": "tool",
                    "tool": "failing_tool",
                    "input": "{{ message }}",
                    "output": "tool_result",
                    "next": "end",
                    "retry": {"max_attempts": 2, "backoff_seconds": 0},
                },
                {"id": "end", "type": "end"},
            ],
        },
        registry,
    )
    executor = SequentialWorkflowExecutor(llm=StaticLLM(), tool_registry=registry)

    with pytest.raises(WorkflowExecutionError) as exc_info:
        executor.run(workflow)

    assert all("tool_result" not in event["context_after_keys"] for event in exc_info.value.trace)
