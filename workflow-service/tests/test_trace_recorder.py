from __future__ import annotations

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.models import Workflow
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry


def load_example_workflow() -> Workflow:
    """加载 Level 1 示例 workflow。"""

    registry = create_default_tool_registry()
    return JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=registry.names())
    ).load("examples/level1_manual_workflow.json")


def test_executor_success_result_contains_trace() -> None:
    """成功执行 workflow 后，应返回每个节点的结构化 trace。"""

    workflow = load_example_workflow()
    registry = create_default_tool_registry()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)

    assert [event["node_id"] for event in result.trace] == [
        "start",
        "plan",
        "search",
        "summarize",
        "end",
    ]
    assert [event["status"] for event in result.trace] == [
        "success",
        "success",
        "success",
        "success",
        "success",
    ]
    assert all("started_at" in event for event in result.trace)
    assert all("ended_at" in event for event in result.trace)
    assert all(event["duration_ms"] >= 0 for event in result.trace)
    assert all(event["attempt"] == 1 for event in result.trace)


def test_llm_trace_records_rendered_prompt_and_output() -> None:
    """LLM trace 应记录渲染后的 prompt 和写入的 output。"""

    workflow = load_example_workflow()
    registry = create_default_tool_registry()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)
    plan_trace = result.trace[1]

    assert plan_trace["node_id"] == "plan"
    assert plan_trace["node_type"] == "llm"
    assert plan_trace["input"] == {
        "prompt": "请为这个目标生成 3 个搜索关键词：总结 AI Agent 工作流系统的核心组成"
    }
    assert plan_trace["output"] == {
        "keywords": ["AI Agent", "Workflow Engine", "Tool Calling"]
    }


def test_tool_trace_records_tool_input_and_output() -> None:
    """Tool trace 应记录工具名、解析后的输入和工具输出。"""

    workflow = load_example_workflow()
    registry = create_default_tool_registry()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)
    search_trace = result.trace[2]

    assert search_trace["node_id"] == "search"
    assert search_trace["node_type"] == "tool"
    assert search_trace["input"] == {
        "tool": "mock_search",
        "tool_input": ["AI Agent", "Workflow Engine", "Tool Calling"],
    }
    assert search_trace["output"] == {
        "search_results": [
            {"title": "Mock result for AI Agent", "source": "mock_search"},
            {"title": "Mock result for Workflow Engine", "source": "mock_search"},
            {"title": "Mock result for Tool Calling", "source": "mock_search"},
        ]
    }


def test_trace_records_context_keys_and_diff_without_full_context_snapshot() -> None:
    """Trace 不保存完整 context 快照，只记录 key 与 diff。"""

    workflow = load_example_workflow()
    registry = create_default_tool_registry()
    executor = SequentialWorkflowExecutor(llm=MockLLM(), tool_registry=registry)

    result = executor.run(workflow)
    search_trace = result.trace[2]

    assert search_trace["context_before_keys"] == ["goal", "keywords"]
    assert search_trace["context_after_keys"] == ["goal", "keywords", "search_results"]
    assert search_trace["context_diff"] == {
        "added": ["search_results"],
        "updated": [],
        "removed": [],
    }
    assert "context_before" not in search_trace
    assert "context_after" not in search_trace


def test_failed_execution_error_carries_failed_trace() -> None:
    """执行失败时，WorkflowExecutionError 应携带已记录的 failed trace。"""

    workflow = Workflow.model_validate(
        {
            "version": "1.0",
            "name": "missing_variable_workflow",
            "nodes": [
                {"id": "start", "type": "start", "next": "plan"},
                {
                    "id": "plan",
                    "type": "llm",
                    "prompt": "目标：{{ missing_goal }}",
                    "output": "answer",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    with pytest.raises(WorkflowExecutionError) as exc_info:
        executor.run(workflow)

    trace = exc_info.value.trace
    assert [event["node_id"] for event in trace] == ["start", "plan"]
    assert trace[-1]["status"] == "failed"
    assert trace[-1]["node_type"] == "llm"
    assert trace[-1]["input"] == {"prompt_template": "目标：{{ missing_goal }}"}
    assert trace[-1]["error"]["type"] == "WorkflowExecutionError"
    assert "failed to execute llm node: plan" in trace[-1]["error"]["message"]


def test_trace_redacts_sensitive_input_and_output_values() -> None:
    """Trace input/output 中的敏感字段应被脱敏。"""

    workflow = JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools={"echo"})
    ).load_data(
        {
            "version": "1.0",
            "name": "redact_trace_workflow",
            "inputs": {
                "payload": {
                    "api_key": "sk-test",
                    "token": "token-value",
                    "safe": "visible",
                }
            },
            "nodes": [
                {"id": "start", "type": "start", "next": "echo"},
                {
                    "id": "echo",
                    "type": "tool",
                    "tool": "echo",
                    "input": "{{ payload }}",
                    "output": "echoed",
                    "next": "end",
                },
                {"id": "end", "type": "end"},
            ],
        }
    )
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    result = executor.run(workflow)
    tool_trace = result.trace[1]

    assert tool_trace["input"]["tool_input"] == {
        "api_key": "[REDACTED]",
        "token": "[REDACTED]",
        "safe": "visible",
    }
    assert tool_trace["output"]["echoed"] == {
        "api_key": "[REDACTED]",
        "token": "[REDACTED]",
        "safe": "visible",
    }
    assert result.context["echoed"]["api_key"] == "sk-test"
