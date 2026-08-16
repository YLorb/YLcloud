from __future__ import annotations

from typing import Any

import pytest

from mini_agent_flow.engine.executor import SequentialWorkflowExecutor, WorkflowExecutionError
from mini_agent_flow.engine.loader import JsonWorkflowLoader
from mini_agent_flow.engine.validator import WorkflowValidator
from mini_agent_flow.llm.mock import MockLLM
from mini_agent_flow.tools.builtin import create_default_tool_registry


def load_condition_workflow(flag: Any, expression: str = "{{ flag }}"):
    """构造一个 start -> condition -> true/false end 的测试 workflow。"""

    return JsonWorkflowLoader(
        validator=WorkflowValidator(allowed_tools=set())
    ).load_data(
        {
            "version": "1.0",
            "name": "condition_workflow",
            "inputs": {"flag": flag},
            "nodes": [
                {"id": "start", "type": "start", "next": "check"},
                {
                    "id": "check",
                    "type": "condition",
                    "expression": expression,
                    "if_true": "true_end",
                    "if_false": "false_end",
                },
                {"id": "true_end", "type": "end"},
                {"id": "false_end", "type": "end"},
            ],
        }
    )


def run_condition_workflow(flag: Any):
    """执行 condition 测试 workflow。"""

    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )
    return executor.run(load_condition_workflow(flag))


def test_condition_true_branch_for_boolean_true() -> None:
    """bool True 应走 if_true 分支。"""

    result = run_condition_workflow(True)

    assert result.executed_nodes == ["start", "check", "true_end"]
    assert result.trace[1]["output"] == {
        "condition_result": True,
        "selected_branch": "if_true",
        "next": "true_end",
    }


def test_condition_false_branch_for_boolean_false() -> None:
    """bool False 应走 if_false 分支。"""

    result = run_condition_workflow(False)

    assert result.executed_nodes == ["start", "check", "false_end"]
    assert result.trace[1]["output"]["selected_branch"] == "if_false"


def test_condition_false_branch_for_empty_list() -> None:
    """空 list 应被判断为 False。"""

    result = run_condition_workflow([])

    assert result.executed_nodes == ["start", "check", "false_end"]
    assert result.trace[1]["input"]["resolved_value"] == []


def test_condition_true_branch_for_non_empty_list() -> None:
    """非空 list 应被判断为 True。"""

    result = run_condition_workflow(["item"])

    assert result.executed_nodes == ["start", "check", "true_end"]
    assert result.trace[1]["input"]["resolved_value"] == ["item"]


def test_condition_false_branch_for_false_string() -> None:
    """字符串 'false' 应被判断为 False。"""

    result = run_condition_workflow("false")

    assert result.executed_nodes == ["start", "check", "false_end"]
    assert result.trace[1]["input"]["resolved_value"] == "false"


def test_condition_trace_records_expression_and_branch() -> None:
    """Condition trace 应记录表达式、解析值、分支和 next。"""

    result = run_condition_workflow(["result"])
    condition_trace = result.trace[1]

    assert condition_trace["node_id"] == "check"
    assert condition_trace["node_type"] == "condition"
    assert condition_trace["status"] == "success"
    assert condition_trace["input"] == {
        "expression": "{{ flag }}",
        "resolved_value": ["result"],
        "if_true": "true_end",
        "if_false": "false_end",
    }
    assert condition_trace["output"] == {
        "condition_result": True,
        "selected_branch": "if_true",
        "next": "true_end",
    }


def test_condition_missing_variable_fails_with_trace() -> None:
    """Condition 引用缺失变量时应失败，并携带 failed trace。"""

    workflow = load_condition_workflow(True, expression="{{ missing_flag }}")
    executor = SequentialWorkflowExecutor(
        llm=MockLLM(),
        tool_registry=create_default_tool_registry(),
    )

    with pytest.raises(WorkflowExecutionError) as exc_info:
        executor.run(workflow)

    condition_trace = exc_info.value.trace[-1]
    assert condition_trace["node_id"] == "check"
    assert condition_trace["status"] == "failed"
    assert condition_trace["input"] == {
        "expression": "{{ missing_flag }}",
        "if_true": "true_end",
        "if_false": "false_end",
    }
    assert condition_trace["error"]["type"] == "WorkflowExecutionError"


def test_condition_does_not_emit_skipped_trace() -> None:
    """当前 Condition 第一版只记录已执行路径，不记录未走分支 skipped。"""

    result = run_condition_workflow(True)

    assert all(event["status"] != "skipped" for event in result.trace)
    assert [event["node_id"] for event in result.trace] == ["start", "check", "true_end"]
